package rso.dfs.model.dao.psql;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rso.dfs.event.DBModificationType;
import rso.dfs.event.DFSEvent;
import rso.dfs.event.UpdateFileEvent;
import rso.dfs.event.UpdateFilesOnServers;
import rso.dfs.event.UpdateServerEvent;
import rso.dfs.model.File;
import rso.dfs.model.FileOnServer;
import rso.dfs.model.Query;
import rso.dfs.model.Server;
import rso.dfs.model.ServerRole;
import rso.dfs.model.dao.DFSModelDAO;
import rso.dfs.model.dao.DFSRepository;

/**
 * Master and Shadows repository
 * 
 * @author Adam Papros <adam.papros@gmail.com>
 * */
public class DFSRepositoryImpl extends Thread implements DFSRepository {

	final static Logger log = LoggerFactory.getLogger(DFSRepository.class);

	/**
	 * Information about master server.
	 * */
	private Server master;

	/**
	 * Master's DAO.
	 * */
	private DFSModelDAO masterDAO;

	/**
	 * Collection for shadows
	 * */
	private HashMap<Server, DFSModelDAO> shadowsMap;

	/**
	 * Communication queue
	 * */
	private BlockingQueue<DFSEvent> blockingQueue;

	private boolean killRepository = false;

	public DFSRepositoryImpl(Server master, BlockingQueue<DFSEvent> blockingQueue) {
		this.master = master;
		this.blockingQueue = blockingQueue;
		this.shadowsMap = new HashMap<>();
	}

	public void addShadow(Server shadow) {
		log.debug("Adding new shadow, shadow=", shadow);
		// add shadow to map
		shadowsMap.put(shadow, new DFSModelDAOImpl(new DFSDataSource(shadow.getIp())));
		// insert data to master's database
		Long shadowId = masterDAO.saveServer(shadow);
		// update object
		shadow.setId(shadowId);
		try {
			// replicate data to daos
			blockingQueue.put(new UpdateServerEvent(shadow, DBModificationType.SAVE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	public void removeShadow(Server shadowToRemove) {
		log.debug("Removing shadow, shadow=", shadowToRemove);
		DFSModelDAO removedDAO = shadowsMap.remove(shadowToRemove);

	}

	@Override
	public void deleteFile(final File file) {
		log.debug("Removing file, file=", file);
		// delete on masterDAO
		int numberOfAffectedRows = masterDAO.deleteFile(file);
		try {
			blockingQueue.put(new UpdateFileEvent(file, DBModificationType.DELETE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Server getMasterServer() {
		// fetch master
		List<Server> list = masterDAO.fetchServersByRole(ServerRole.MASTER);

		if (list.size() > 1) {
			// raise fatal error AND WRITE LOG MESSAGE
			log.error("More than one master found in DB, core panic");
			System.exit(-1);

		} else if (list.isEmpty()) {
			return null;
		}
		return list.get(0);
	}

	@Override
	public void saveServer(Server server) {
		log.debug("Saving server, server=", server);
		Long serverId = masterDAO.saveServer(server);
		server.setId(serverId);
		try {
			blockingQueue.put(new UpdateServerEvent(server, DBModificationType.SAVE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public File getFileByFileName(String fileName) {
		log.debug("Fetching file, fileName=" + fileName);
		return masterDAO.fetchFileByFileName(fileName);
	}

	@Override
	public Server getSlaveByFile(File file) {
		log.debug("Fetching servers with file: " + file.getName());

		List<Server> servers = masterDAO.fetchServersByFileId(file.getId());

		// TODO :choose server !
		if (servers == null || servers.isEmpty()) {
			// raise fatal error
		}

		return servers.get(0);
	}

	@Override
	public List<Server> getSlaves() {
		log.debug("Fetching slaves.");
		return masterDAO.fetchServersByRole(ServerRole.SLAVE);
	}

	@Override
	public List<Server> getShadows() {
		log.debug("Fetching shadows.");
		return masterDAO.fetchServersByRole(ServerRole.SHADOW);
	}

	@Override
	public File getFileById(Integer fileId) {
		log.debug("Fetching file by id, fileId=", fileId);
		return masterDAO.fetchFileById(fileId);
	}

	@Override
	public void saveFileOnServer(FileOnServer fileOnServer) {
		log.debug("Saving fileOnServer, fileOnServer=", fileOnServer);
		masterDAO.saveFileOnServer(fileOnServer);
		try {
			blockingQueue.put(new UpdateFilesOnServers(fileOnServer, DBModificationType.SAVE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public List<File> getFilesOnSlave(Server slave) {
		log.debug("Fetching files on slave,slave=", slave);
		return masterDAO.fetchFilesOnServer(slave);
	}

	@Override
	public Integer saveFile(final File file) {
		log.debug("Saving file, file=", file);
		Integer fileId = masterDAO.saveFile(file);
		file.setId(fileId);
		try {
			blockingQueue.put(new UpdateFileEvent(file, DBModificationType.SAVE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return fileId;

	}

	@Override
	public void updateFile(final File file) {
		log.debug("Updating file,file=", file);
		int numberOfAffectedRows = masterDAO.updateFile(file);
		try {
			blockingQueue.put(new UpdateFileEvent(file, DBModificationType.UPDATE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void deleteFileOnServer(FileOnServer fileOnServer) {
		log.debug("Deleting fileOnServer, fileOnServer=", fileOnServer);
		int numberOfAffectedRows = masterDAO.deleteFileOnServer(fileOnServer);

		try {
			blockingQueue.put(new UpdateFilesOnServers(fileOnServer, DBModificationType.DELETE));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	@Override
	public List<Server> getSlavesByFile(File file) {
		log.debug("Fetching slaves by file, file=", file);
		return masterDAO.fetchServersByFileId(file.getId());

	}

	@Override
	public void cleanDB() {
		masterDAO.cleanDB();
	}

	@Override
	public List<File> getAllFiles() {
		log.debug("Fetching all files.");
		return masterDAO.fetchAllFiles();
	}

	@Override
	public void run() {

		while (!killRepository) {
			try {
				DFSEvent e = blockingQueue.take();
				for (Entry<Server, DFSModelDAO> entry : shadowsMap.entrySet()) {
					// set dao
					e.setDao(entry.getValue());
					// execute command
					e.execute();
				}

			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

	}

	public void killRepository() {
		this.killRepository = true;
	}

	@Override
	public Server getServerByIp(String serverIp) {
		log.debug("Fetching server by ip,serverIp=", serverIp);
		return masterDAO.fetchServerByIp(serverIp);
	}

	@Override
	public List<Query> getQueriesAfter(long version) {
		log.debug("Fetching queries after,version=", version);
		return masterDAO.fetchQueriesAfter(version);
	}

	@Override
	public List<Query> getAllQueries() {
		log.debug("Fetching all queries.");
		return masterDAO.fetchAllQueries();
	}

}
