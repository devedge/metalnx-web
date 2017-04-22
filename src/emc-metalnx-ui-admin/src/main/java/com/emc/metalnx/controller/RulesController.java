/*
 * Copyright (c) 2015-2017, Dell EMC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.emc.metalnx.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import javax.servlet.ServletException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.emc.metalnx.core.domain.exceptions.DataGridException;
import com.emc.metalnx.services.interfaces.*;
import com.emc.metalnx.core.domain.exceptions.DataGridConnectionRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.metalnx.services.interfaces.IRODSServices;
import com.emc.metalnx.services.interfaces.UserService;

/********** iRODS File transfer imports ***************/
import org.irods.jargon.core.pub.DataTransferOperations;
import org.irods.jargon.core.pub.IRODSFileSystem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

// Imports to print full stack traces to the logs
import java.io.StringWriter;
import java.io.PrintWriter;

import org.irods.jargon.core.connection.IRODSAccount;
import org.irods.jargon.core.connection.SettableJargonProperties;
import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.exception.OverwriteException;
import org.irods.jargon.core.pub.io.IRODSFile;
import org.irods.jargon.core.pub.io.IRODSFileFactory;

@Controller
@Scope(WebApplicationContext.SCOPE_SESSION)
@RequestMapping(value = "/rules")
public class RulesController {

    @Autowired
    UserService userService;

    @Autowired
    IRODSServices irodsServices;
	
	@Autowired
    ResourceService resourceService;
	
	
	private static final Logger logger = LoggerFactory.getLogger(RulesController.class);

    /**
     * Responds to the rules request
     * Minimal rules controller to handle webpage routing
     *
     * @param model
     * @return the template with the rules page
     */
    @RequestMapping(value = "/")
    public String listrules(Model model) throws DataGridConnectionRefusedException {
		
		try {
			model.addAttribute("resources", resourceService.findAll());
			// logger.info("ALL DATA RETURNED FROM RESOURCE SERVICE {}", resourceService.findAll());
			
		} catch (DataGridException e) {
			logger.error("Could not respond to request for collections: {}", e);
            model.addAttribute("unexpectedError", true);
		}
		
        return "rules/rules";
    }

    @RequestMapping(value = "deployNewRule/", method = RequestMethod.POST, produces = {"text/plain"})
    @ResponseStatus(value = HttpStatus.OK)
    public ResponseEntity<?> deployNewRule(Model model, HttpServletRequest request) throws JargonException, IOException, ServletException, JSONException {
        JSONObject jsonUploadMsg = new JSONObject();
        HttpStatus status = HttpStatus.OK;

        try {
            MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
            MultipartFile multipartFile = multipartRequest.getFile("file");
            File file = convert(multipartFile);
            logger.info("-------> File received: {}",  multipartFile.getOriginalFilename());

            try {
                jsonUploadMsg.put("filename", multipartFile.getOriginalFilename());
                jsonUploadMsg.put("httpstatus", "OK");
            } catch (JSONException e) {
                logger.info("-------> Error with json response.");
                logger.error("Could not create JSON object for upload response: {]", e.getMessage());
                jsonUploadMsg.put("httpstatus", "BAD");
            } catch (Exception e) {
                logger.info("-------> Non-json exception happened.");
            }

        } catch (Exception e) {
            logger.info("-------> Unknown exception in deployNewRule()");
            logger.info(e.getMessage());
			
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			String excToString = sw.toString();
			logger.info("-------> Exception Stack Trace {}", excToString);
			
            jsonUploadMsg.put("httpstatus", "BAD");
            jsonUploadMsg.put("error", e.getMessage());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        if (!(request instanceof MultipartHttpServletRequest)) {
            logger.info("-------> (DEBUG): Request is not multipart request.");
            logger.debug("Request is not a multipart request.");
        }

        
    
        return new ResponseEntity<>(jsonUploadMsg.toString(),  HttpStatus.OK);
    }

    /*
     * **************************************************************************
     * **************************** PRIVATE METHODS *****************************
     * **************************************************************************
     */

    /**
     * Takes a multipart file (httpservlet) and converts it to a Java IO File.
     * @param MultipartFile file    the file received from ajax call
     * @return File convFile        the converted file
     * @throws IOException
     * @throws FileNotFoundException
     */
    public File convert(MultipartFile file) throws IOException, FileNotFoundException {
		boolean success = (new File("/tmp/emc-tmp-rules/")).mkdirs();
        File convFile = new File("/tmp/emc-tmp-rules/" + file.getOriginalFilename());
		convFile.createNewFile(); 
        FileOutputStream fos = new FileOutputStream(convFile); 
        fos.write(file.getBytes());
        fos.close(); 
        return convFile;
    }

    private static IRODSFileSystem irodsFileSystem = null;
    // private static IRODSFileFactory irodsFileFactory = irodsServices.getIRODSFileFactory();

    final int PORT = 1247;
    final String HOME_DIR = "/tempZone/home/rods";
    final String ZONE = "tempZone";
    final String RESOURCE = "demoResc";
    
    // path for iRODS grid
    String IRODS_PATH = "/tempZone/home/rods/.rulecache/";
    
    // print streams
    PrintStream originalOut;
    PrintStream originalErr;

    /**
     * Transmits a command to an iRODS host.
     * @param file          The input file
     * @param command       [deploy, delete]
     * @param index         based on number of servers on the grid
     * @param host          the name of the host you are transmitting to
     * @param user          the user who is currently transmitting a file
     * @param password      the password user trasmitting a file
     * @return String       a timestamp of the transmission
     * @throws JargonException
     */
    public String transmit(File file, String command, int index, String host, String user, String password) throws JargonException {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        File newFile = null;
        
        try {
            newFile = prepareFile(command, index, timestamp, file);
            putFile(newFile, host, user, password);
            getResponse(newFile.getName(), host, user, password);
            newFile.delete();
        } catch (OverwriteException e) {
            originalErr.println("File " + file.getName() + " already exists on target.");
            e.printStackTrace(originalErr);
        }  catch (IOException e) {
            e.printStackTrace(originalErr);
        }
        newFile.delete();
        return timestamp;
    }


    public File prepareFile(String command, int index, String timestamp, File file) throws FileNotFoundException, IOException {
        String indexString = Integer.toString(index);
        String newfileName = indexString + "_" + timestamp + "-" + file.getName();
        
        // open streams
        OutputStreamWriter os = new OutputStreamWriter(new FileOutputStream(new File(newfileName)));
        BufferedReader br = new BufferedReader(new FileReader(file));
        
        // create new file, prepended with command
        os.write(command + "\n");
        String line;
        while ((line = br.readLine()) != null) {
            os.write(line + "\n");
        }
        
        // close streams
        os.close();
        br.close();
        
        File newFile = new File(newfileName);
        return newFile;
    }

    public int putFile(File localFile, String host, String user, String password) throws JargonException {
        originalOut.println("PUT " + localFile.getName());
        
        // create files
        String targetIrodsFile = IRODS_PATH + localFile.getName();
        
        // authorize with iRODS
        IRODSAccount irodsAccount = new IRODSAccount(host, PORT, user, password, HOME_DIR, ZONE, RESOURCE);
        IRODSFileFactory irodsFileFactory = irodsFileSystem.getIRODSFileFactory(irodsAccount);
        IRODSFile iRODSFile = irodsFileFactory.instanceIRODSFile(targetIrodsFile);
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);

        // transfer file to iRODS grid
        dataTransferOperationsAO.putOperation(localFile, iRODSFile, null, null);
        
        return 0;
    }

    public int getResponse(String filename, String host, String user, String password) throws JargonException {
        originalOut.println("GET " + filename + ".res\n");
        
        // authorize with iRODS
        IRODSAccount irodsAccount = new IRODSAccount(host, PORT, user, password, HOME_DIR, ZONE, RESOURCE);
        IRODSFileFactory irodsFileFactory = irodsFileSystem.getIRODSFileFactory(irodsAccount);
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);
        
        // generate the files
        // localFile
        File localFile = new File("output_" + filename + ".res");
        if (localFile.exists()) {
            localFile.delete();
        }
        // iRODS file
        String iRODSFilename = IRODS_PATH + filename + ".res";
        IRODSFile iRODSFile = irodsFileFactory.instanceIRODSFile(iRODSFilename);
        
        // get operation - retry until success. checks once per second
        boolean done = false;
        while (!done) {
            try {
                dataTransferOperationsAO.getOperation(iRODSFile, localFile, null, null);
                done = true;
            } catch (JargonException e) {
                if (!e.getMessage().equals("File not found")) {
                    throw e;
                }
                
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    ie.printStackTrace(originalErr);
                };
            }
        }
        
        return 0;
    }


}

