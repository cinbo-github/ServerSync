package com.superzanti.serversync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.superzanti.serversync.util.Logger;
import com.superzanti.serversync.util.PathUtils;
import com.superzanti.serversync.util.Server;
import com.superzanti.serversync.util.SyncFile;

import runme.Main;

/**
 * Deals with all of the synchronizing for the client, this works without
 * starting minecraft
 * 
 * @author Rheimus
 *
 */
public class ClientWorker implements Runnable {

	private boolean errorInUpdates;
	private boolean updateHappened;
	private boolean finished;
	private Logger logs;

	private List<SyncFile> clientFiles;

	public ClientWorker() {
		logs = new Logger();
		clientFiles = new ArrayList<SyncFile>();
		errorInUpdates = false;
		updateHappened = false;
		finished = false;

		final class Shutdown extends Thread {

			@Override
			public void run() {
				logs.save();
			}

		}
		// Save full log on shutdown
		Runtime.getRuntime().addShutdownHook(new Shutdown());

	}

	public Logger getLogger() {
		return logs;
	}

	public boolean getErrors() {
		return errorInUpdates;
	}

	public boolean getUpdates() {
		return updateHappened;
	}

	public boolean isFinished() {
		return finished;
	}

	private void closeWorker(Server server) {
		server.close();
		if (!finished) {
			if (!updateHappened && !errorInUpdates) {
				Main.updateText(Main.strings.getString("update_not_needed"));
				Main.updateProgress(100);
			} else {
				logs.updateLogs(Main.strings.getString("update_happened"), Logger.FULL_LOG);
				Main.updateProgress(100);
			}
			if (errorInUpdates) {
				logs.updateLogs(Main.strings.getString("update_error"));
			}
			Main.toggleButton();
			finished = true;
		} else {
			Main.toggleButton();
		}

	}

	@Override
	public void run() {
		Server server = new Server(this, SyncConfig.SERVER_IP, SyncConfig.SERVER_PORT);
		boolean updateNeeded = false;
		updateHappened = false;

		if (!server.connect()) {
			errorInUpdates = true;
			finished = true;
			return;
		}

		ArrayList<String> syncableDirectories = server.getSyncableDirectories();
		if (syncableDirectories == null) {
			errorInUpdates = true;
			finished = true;
			return;
		}
		
		List<Path> clientConfigPaths = PathUtils.fileListDeep(Paths.get("../config/"));
		List<Path> clientFilePaths = new ArrayList<>();
		for (String directory : syncableDirectories) {
			if (directory.equals("config")) {
				continue;
			}
			
			List<Path> _files = PathUtils.fileListDeep(Paths.get("../"+directory+"/"));
			if (_files != null) {
				clientFilePaths.addAll(_files);
			}
		}
		

		// Populate Clients SyncFiles ////////////////////////////////
		if (!clientFilePaths.isEmpty()) {
			for (Path path : clientFilePaths) {
				String name = path.getFileName().toString();
				if (!SyncConfig.IGNORE_LIST.contains(name)) {	
					SyncFile _clientFile = null;
					try {
						_clientFile = new SyncFile(path);
						clientFiles.add(_clientFile);
					} catch (IOException e) {
						logs.updateLogs("Failed to create SyncFile for: (" + name + ")", Logger.FULL_LOG);
						errorInUpdates = true;
					}					
				}
			}
		} else {
			logs.updateLogs(Main.strings.getString("no_syncable_directories"));
			errorInUpdates = true;
			finished = true;
			return;
		}
		//////////////////////////////////////////////////////////////
		
		if (clientConfigPaths != null) {
			for (Path path : clientConfigPaths) {
				String configFileName = path.getFileName().toString();
				if (SyncConfig.INCLUDE_LIST.contains(configFileName) && !configFileName.equals("serversync.cfg")) {
					SyncFile _clientConfig = null;
					try {
						_clientConfig = new SyncFile(path, false);
						clientFiles.add(_clientConfig);
					} catch (IOException e) {
						logs.updateLogs("Failed to create SyncFile for: (" + configFileName + ")", Logger.FULL_LOG);
						errorInUpdates = true;
					}
				}
			}
		}

		logs.updateLogs(Main.strings.getString("config_check"));
		
		try {
			server.getConfig();
		} catch (IOException e) {
			logs.updateLogs("Failed to obtain config from server");
			errorInUpdates = true;
			finished = true;
			return;
		}

		// Get keys from the server /////////////////////////////////
		if (!server.getSecurityDetails()) {
			logs.updateLogs(Main.strings.getString("failed_handshake"));
			errorInUpdates = true;
			finished = true;
			return;
		}
		/////////////////////////////////////////////////////////////

		logs.updateLogs("Checking if update is needed", Logger.FULL_LOG);
		updateNeeded = server.isUpdateNeeded(clientFiles);

		/* MAIN PROCESSING CHUNK */
		if (updateNeeded) {
			logs.updateLogs("Update required", Logger.FULL_LOG);
			updateHappened = true;
			logs.updateLogs(Main.strings.getString("mods_incompatable"));
			logs.updateLogs("<------> "+ "Getting files" +" <------>");

			// get all files on server
			logs.updateLogs(Main.strings.getString("mods_get"));
			ArrayList<SyncFile> serverFiles = server.getFiles();
			
			if (serverFiles == null) {
				logs.updateLogs("Failed to get files from server, check detailed log in minecraft/logs");
				errorInUpdates = true;
				finished = true;
				return;
			}
			
			if (serverFiles.isEmpty()) {
				logs.updateLogs("Server has no syncable files");
				finished = true;
				return;
			}
			
			/* CLIENT MODS */
			if (!SyncConfig.REFUSE_CLIENT_MODS) {
				logs.updateLogs(Main.strings.getString("mods_accepting_clientmods"));

				ArrayList<SyncFile> serverClientOnlyMods = server.getClientOnlyFiles();

				if (serverClientOnlyMods == null) {
					logs.updateLogs("Failed to access servers client only mods");
					errorInUpdates = true;
				} else {					
					serverFiles.addAll(serverClientOnlyMods);
				}
			} else {
				logs.updateLogs(Main.strings.getString("mods_refusing_clientmods"));
			}

			logs.updateLogs(Main.strings.getString("ignoring") + " " + SyncConfig.IGNORE_LIST, Logger.FULL_LOG);
			
			// run calculations to figure out how big the progress bar is
			float numberOfFiles = clientFiles.size() + serverFiles.size();
			float percentScale = numberOfFiles / 100;
			float currentPercent = 0;

			/* UPDATING */
			logs.updateLogs("<------> "+ Main.strings.getString("update_start") +" <------>");

			/* COMMON MODS */
			for (SyncFile file : serverFiles) {
				// Update status
				currentPercent++;

				Path clientPath = file.CLIENT_MODPATH;
				// Get file at rPath location
				boolean exists = Files.exists(clientPath);

				// Exists
				if (exists) {
					
					SyncFile clientFile = null;
					try {
						clientFile = new SyncFile(clientPath);
					} catch (IOException e) {
						logs.updateLogs("Failed to create SyncFile for (" + clientPath + ")", Logger.FULL_LOG);
						errorInUpdates = true;
					}
					
					if (clientFile != null) {						
						if (!clientFile.compare(file)) {
							server.updateFile(file.MODPATH.toString(), clientPath.toFile());
						} else {
							logs.updateLogs(file.fileName + " " + Main.strings.getString("up_to_date"), Logger.FULL_LOG);
						}
					}
				} else {
					// only need to check for ignore here as we are working
					// on the servers file tree
					if (file.isSetToIgnore() && !file.clientOnlyMod) {
						logs.updateLogs("<>"+ Main.strings.getString("ignoring") + " " + file.fileName);
					} else {
						logs.updateLogs(file.fileName + " " + Main.strings.getString("does_not_exist"), Logger.FULL_LOG);
						server.updateFile(file.MODPATH.toString(), clientPath.toFile());
					}
				}
				
				Main.updateProgress((int) (currentPercent / percentScale));
			}

			/* DELETION */
			logs.updateLogs("<------> "+ Main.strings.getString("delete_start") +" <------>");
			// Parse clients file tree
			for (SyncFile file : clientFiles) {
				currentPercent++;

				// check for files that need to be deleted
				if (file.isSetToIgnore()) {
					logs.updateLogs(Main.strings.getString("ignoring") + " " + file.fileName, Logger.FULL_LOG);
				} else {
					// Not present in server list
					logs.updateLogs(Main.strings.getString("client_check") + " " + file.fileName, Logger.FULL_LOG);
					
					boolean exists = server.modExists(file);
					
					if (!exists) {
						logs.updateLogs(file.fileName + " " + Main.strings.getString("does_not_match") +  Main.strings.getString("delete_attempt"),
								Logger.FULL_LOG);

						// File fails to delete
						try {
							file.delete();
							logs.updateLogs("<>" + file.fileName + " " + Main.strings.getString("delete_success"));
						} catch (IOException e) {
							logs.updateLogs(file.fileName + " " + Main.strings.getString("delete_fail"),
									Logger.FULL_LOG);
							file.deleteOnExit();
						}

						updateHappened = true;
					}
					Main.updateProgress((int) (currentPercent / percentScale));
				}
			}
		}

		server.exit();

		logs.updateLogs(Main.strings.getString("update_complete"));
		
		
		// Teardown
		closeWorker(server);

	}

}
