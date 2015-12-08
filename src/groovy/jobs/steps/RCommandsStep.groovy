package jobs.steps

import com.recomdata.transmart.util.RUtil
import groovy.text.SimpleTemplateEngine
import groovy.util.logging.Log4j
import jobs.UserParameters
import org.rosuda.REngine.REXP
import org.rosuda.REngine.Rserve.RConnection
import org.rosuda.REngine.Rserve.RserveException
import rcloud.RCloudConnection
import uk.ac.ebi.rcloud.server.RType.RObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.codehaus.groovy.grails.commons.ApplicationHolder

@Log4j
class RCommandsStep implements Step {

    final String statusName = 'Running analysis'

    File temporaryDirectory
    File scriptsDirectory
    UserParameters params
    List<String> rStatements
    String studyName /* see comment on AbstractAnalysisJob::studyName */

    def grailsApplication = ApplicationHolder.application
    def useRCloud = grailsApplication.config.RModules.useRCloud

    /**
     * This map allows us to pass information that is only available later on
     */
    Map<String, String> extraParams

    @Override
    void execute() {
        runRCommandList rStatements
    }

    final private void runRCommandList(List<String> stepList) {
        //Establish a connection to R Server.

        Object rConnection

        if (useRCloud) {
            log.info "useRCloud: $useRCloud using RCloudConnection"
            rConnection = new RCloudConnection();
        } else {
            log.info "useRCloud: $useRCloud using RConnection"
            rConnection = new RConnection()
        }

        try {
            //Run the R command to set the working directory to our temp directory.
            String wd = RUtil.escapeRStringContent(temporaryDirectory.absolutePath)
            log.info "About to trigger R command: setwd('$wd')"

            rConnection.eval "setwd('${wd}')"

            /**
             * Please make sure that any and all variables you add to the map here are placed _after_ the putAll
             * as otherwise you create a potential security vulnerability
             */
            Map vars = [:]

            params.each { k,v ->
                vars[k] = v
            }

            //lazy extra params are added after user params, to make sure there are no security breaches
            if (extraParams) {
                vars.putAll(extraParams)
            }

            vars.pluginDirectory = scriptsDirectory.absolutePath
            vars.temporaryDirectory = new File(temporaryDirectory, "subset1_$studyName").absolutePath

            //For each R step there is a list of commands.
            stepList.each { String currentCommand ->
                runRCommand rConnection, currentCommand, vars
            }
        } finally {
            rConnection.close()
        }
    }


    private void runRCommand(Object connection, String command, Map vars) {
        String finalCommand = processTemplates command, vars
        log.info "About to trigger R command: $finalCommand"

        if (connection instanceof RCloudConnection) {
            // Copy script to the folder with data
            // so that R Cloud can see it 
            int sourceIndex = finalCommand.indexOf("source");
            if (sourceIndex != -1) {
                finalCommand = copyScriptAndFixCommand(finalCommand, sourceIndex);
            }

            RObject rObject = connection.parseAndEval("try($finalCommand, silent=FALSE)")

            if (RCloudConnection.inherits(rObject, "try-error")) {
                log.error "R command failure for:$finalCommand"
                handleRError(rObject, connection)
            }            
        } else {
            REXP rObject = connection.parseAndEval("try($finalCommand, silent=FALSE)")

            if (rObject.inherits("try-error")) {
                log.error "R command failure for:$finalCommand"
                handleRError(rObject, connection)
            }
        }

        log.info "R command: $finalCommand returned"
    }

    private String copyScriptAndFixCommand(String finalCommand, int sourceIndex) {

        int openIndex = finalCommand.indexOf("(", sourceIndex);
        if (openIndex == -1) {
            return finalCommand;
        }

        int closeIndex = finalCommand.indexOf(")", openIndex);
        if (closeIndex == -1) {
            return finalCommand;
        }

        String pathInQuotes = finalCommand.substring(openIndex + 1, closeIndex);
        String[] commandArr = pathInQuotes.split("\"");
        if (commandArr.length > 1) {
        } else {
            commandArr = pathInQuotes.split("'");
        }

        String[] pathArr = commandArr[1].split(File.separator);
        String fname = pathArr[pathArr.length - 1];

        Path source = Paths.get(commandArr[1]);
        Path target = Paths.get(temporaryDirectory.absolutePath).resolve(fname);

        log.info("Copying " + source.toString() + " to " + target.toString());

        try {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Copied");
        } catch (Exception ex) {
            log.error("Error!", ex);
        }
        return finalCommand.replace(pathInQuotes, "'" + target.toString() + "'");
    }


    static private void handleRError(Object rObject, Object rConnection) throws RserveException {
        log.info "Handling Error"

        if (rConnection instanceof RCloudConnection) {
            throw new RserveException(null,
                    'There was an error running the R script for your job. ' +
                            "Details: i${ RCloudConnection.getString( (RObject) rObject ) }")
        } else {
            throw new RserveException((RConnection)rConnection,
                    'There was an error running the R script for your job. ' +
                            "Details: ${ (REXP) rObject.asString()}")
        }
    }

    static private String processTemplates(String template, Map vars) {
        Map escapedVars = [:].withDefault { k ->
            if (vars.containsKey(k)) {
                RUtil.escapeRStringContent vars[k].toString()
            }
        }
        SimpleTemplateEngine engine = new SimpleTemplateEngine()
        engine.createTemplate(template).make(escapedVars)
    }
}
