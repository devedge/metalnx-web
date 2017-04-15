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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.WebApplicationContext;

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
	
	
	private static final Logger logger = LoggerFactory.getLogger(CollectionController.class);

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
			logger.info("ALL DATA RETURNED FROM RESOURCE SERVICE {}", resourceService.findAll());
			
		} catch (DataGridException e) {
			logger.error("Could not respond to request for collections: {}", e);
            model.addAttribute("unexpectedError", true);
		}
		
        return "rules/rules";
    }


    /**
     * 
     * 
     * @param filename      The name of the file to be deployed.
     * @param command       Should be "deploy" when deploying a rule.
     * @param index         The index of the file within the batch
     * @param host          The hostname of the iRODS server we are deploying to.
     * @param user          The admin user who is deploying a rule.
     * @param password      The password of the admin user who is deploying a rule.
     * @return treeView template that renders all nodes of certain path (parent)
     * @throws DataGridConnectionRefusedException
     */
    @RequestMapping(value = "/deployNewRule/", method = RequestMethod.POST)
    public String deployNewRule(Model model, @RequestParam("localpath") String localpath) throws JargonException {
        // TODO: Code from iRODSPutFileTest.uploadWithFileVerification()
        logger.info("Local path: {}", localpath);

        String command = "deploy";
        int index = 0;
        String host = "sd-vm14.csc.ncsu.edu";
        String user = "rods";
        String password = "irods";

        // for (server : server_list) { trasmit(); }
        int response = transmit(localpath, command, index, host, user, password);
        logger.info("Put file response: {}", response);

        return "rules/rules";
    }

    /*
     * **************************************************************************
     * **************************** PRIVATE METHODS *****************************
     * **************************************************************************
     */

    private static IRODSFileSystem irodsFileSystem = null;

    final int PORT = 1247;
    final String HOME_DIR = "/tempZone/home/rods";
    final String ZONE = "tempZone";
    final String RESOURCE = "demoResc";
    
    // path for iRODS grid
    String IRODS_PATH = "/tempZone/home/rods/.rulecache/";
    
    // print streams
    PrintStream originalOut;
    PrintStream originalErr;

    private int transmit(String filename, String command, int index, String host, String user, String password) throws JargonException {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        
        String newFilename;

        int response = -1;
        
        try {
            newFilename = prepareFile(command, index, timestamp, filename);
            response = putFile(newFilename, host, user, password);
            getResponse(newFilename, host, user, password);
            new File(newFilename).delete();
        } catch (OverwriteException e) {
            originalErr.println("File " + filename + " already exists on target.");
            e.printStackTrace(originalErr);
        }  catch (IOException e) {
            e.printStackTrace(originalErr);
        }
        
        // return timestamp; // TODO: why are you returning the time stamp? why don't you just return 0 or 1
        return response;
    }
    
    /**
     * Puts a file up to the iRODS grid resource server
     * Files can be removed by running "/home/dlgross/removeFiles.txt"
     * 
     * @param filename
     * @return 0 if completed successful
     * @throws JargonException - Will throw an exception if the files are already on the remote servers
     *                      Must remove them from remote server using "irm <filename>"
     */
    private int putFile(String filename, String host, String user, String password) throws JargonException {
        originalOut.println("PUT " + filename);
        
        // create files
        String targetIrodsFile = IRODS_PATH + filename;
        File localFile = new File(filename);
        
        // authorize with iRODS
        IRODSAccount irodsAccount = new IRODSAccount(host, PORT, user, password, HOME_DIR, ZONE, RESOURCE);
        IRODSFileFactory irodsFileFactory = irodsFileSystem.getIRODSFileFactory(irodsAccount);
        IRODSFile iRODSFile = irodsFileFactory.instanceIRODSFile(targetIrodsFile);
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);

        // transfer file to iRODS grid
        dataTransferOperationsAO.putOperation(localFile, iRODSFile, null, null);
        
        return 0;
    }

    /**
     * Function to attempt to get file back from iRODS server. Attempts every 1s until success or
     * an error other than "File not found"
     * 
     * @param timestamp     timestamp to ensure unique filenames on iRODS server
     * @param filename      local filename
     * @return              results integer
     * @throws JargonException 
     */
    private int getResponse(String filename, String host, String user, String password) throws JargonException {
        originalOut.println("GET " + filename + ".res");
        
        // authorize with iRODS
        IRODSAccount irodsAccount = new IRODSAccount(host, PORT, user, password, HOME_DIR, ZONE, RESOURCE);
        IRODSFileFactory irodsFileFactory = irodsFileSystem.getIRODSFileFactory(irodsAccount);
        DataTransferOperations dataTransferOperationsAO = irodsFileSystem.getIRODSAccessObjectFactory().getDataTransferOperations(irodsAccount);
        
        // generate the files
        // localFile
        File localFile = new File("output_" + filename + "s");
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
    
    /**
     * Creates a new file with the appropriate timestamped name
     * prepends the given file contents with the given command
     * @param command
     * @param index
     * @param timestamp
     * @param filename
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private String prepareFile(String command, int index, String timestamp, String filename) throws FileNotFoundException, IOException {
        String indexString = Integer.toString(index);
        String newfileName = indexString + "_" + timestamp + "-" + filename;
        
        // open streams
        OutputStreamWriter os = new OutputStreamWriter(new FileOutputStream(new File(newfileName)));
        BufferedReader br = new BufferedReader(new FileReader(new File(filename)));
        
        // create new file, prepended with command
        os.write(command + "\n");
        String line;
        while ((line = br.readLine()) != null) {
            os.write(line + "\n");
        }
        
        // close streams
        os.close();
        br.close();
        
        return newfileName;
    }

}

