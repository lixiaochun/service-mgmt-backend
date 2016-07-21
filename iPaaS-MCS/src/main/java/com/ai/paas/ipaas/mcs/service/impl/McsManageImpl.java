package com.ai.paas.ipaas.mcs.service.impl;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.paas.agent.client.AgentClient;
import com.ai.paas.ipaas.PaasException;
import com.ai.paas.ipaas.ServiceUtil;
import com.ai.paas.ipaas.agent.util.AgentUtil;
import com.ai.paas.ipaas.agent.util.AidUtil;
import com.ai.paas.ipaas.base.dao.interfaces.IpaasImageResourceMapper;
import com.ai.paas.ipaas.base.dao.interfaces.IpaasSysConfigMapper;
import com.ai.paas.ipaas.base.dao.mapper.bo.IpaasImageResource;
import com.ai.paas.ipaas.base.dao.mapper.bo.IpaasImageResourceCriteria;
import com.ai.paas.ipaas.base.dao.mapper.bo.IpaasSysConfig;
import com.ai.paas.ipaas.base.dao.mapper.bo.IpaasSysConfigCriteria;
import com.ai.paas.ipaas.ccs.constants.ConfigCenterDubboConstants.PathType;
import com.ai.paas.ipaas.ccs.service.ICCSComponentManageSv;
import com.ai.paas.ipaas.ccs.service.dto.CCSComponentOperationParam;
import com.ai.paas.ipaas.mcs.dao.interfaces.McsResourcePoolMapper;
import com.ai.paas.ipaas.mcs.dao.interfaces.McsUserCacheInstanceMapper;
import com.ai.paas.ipaas.mcs.dao.mapper.bo.McsResourcePool;
import com.ai.paas.ipaas.mcs.dao.mapper.bo.McsResourcePoolCriteria;
import com.ai.paas.ipaas.mcs.dao.mapper.bo.McsUserCacheInstance;
import com.ai.paas.ipaas.mcs.dao.mapper.bo.McsUserCacheInstanceCriteria;
import com.ai.paas.ipaas.mcs.service.constant.McsConstants;
import com.ai.paas.ipaas.mcs.service.interfaces.IMcsSv;
import com.ai.paas.ipaas.mcs.service.util.McsParamUtil;
import com.ai.paas.ipaas.mcs.service.util.McsProcessInfo;
import com.ai.paas.ipaas.util.Assert;
import com.ai.paas.ipaas.util.CiperUtil;
import com.ai.paas.ipaas.util.DateTimeUtil;
import com.google.gson.JsonObject;

@Service
@Transactional(rollbackFor = Exception.class)
public class McsManageImpl implements IMcsSv {
	private static transient final Logger logger = LoggerFactory.getLogger(McsManageImpl.class);

	@Autowired 
	private McsSvHepler mcsSvHepler;
	
	@Autowired 
	private ICCSComponentManageSv iCCSComponentManageSv;
	
	@Override
	public String openMcs(String param) throws PaasException {
		Map<String, String> paraMap = McsParamUtil.getParamMap(param);
		String userId = paraMap.get(McsConstants.USER_ID);
		String serviceId = paraMap.get(McsConstants.SERVICE_ID);
		String haMode = paraMap.get(McsConstants.HA_MODE);
 
		if (existsService(userId, serviceId)) {
			logger.info("用户服务已存在，开通成功.");
			return McsConstants.SUCCESS_FLAG;
		}
		
		//TODO:根据userId，serviceId，查看zk中是否存在node。
		
		switch(haMode) {
		case McsConstants.MODE_SINGLE:
			logger.info("---- 开通单节点的MCS服务 ----");
			openSingleMcs(paraMap);
			break;
		case McsConstants.MODE_CLUSTER:
			logger.info("---- 开通集群模式的MCS服务 ----");
			openClusterMcs(paraMap);
			break;
		case McsConstants.MODE_REPLICATION:
			logger.info("---- 开通主从模式的MCS服务 ----");
			openReplicationMcs(paraMap);
			break;
		case McsConstants.MODE_SENTINEL:
			logger.info("---- 开通sentinel模式的MCS服务 ----");
			openSentinelMcs(paraMap);
			break;
		}
		
		return McsConstants.SUCCESS_FLAG;
	}
	
	/**
	 * 开通单例模式的Mcs服务。
	 * @param paraMap
	 * @return
	 * @throws Exception
	 */
	private String openSingleMcs(Map<String, String> paraMap) throws PaasException {
		String userId = paraMap.get(McsConstants.USER_ID);
		String serviceId = paraMap.get(McsConstants.SERVICE_ID);
		String serviceName = paraMap.get(McsConstants.SERVICE_NAME);
		String capacity = paraMap.get(McsConstants.CAPACITY);
		Integer cacheSize = Integer.parseInt(capacity);
		String basePath = AgentUtil.getAgentFilePath(AidUtil.getAid());

		/** 1.获取mcs资源. **/
		McsResourcePool mcsResourcePool = selectMcsResSingle(cacheSize, 1);
		String hostIp = mcsResourcePool.getCacheHostIp();
		Integer cachePort = mcsResourcePool.getCachePort();
		String requirepass = mcsSvHepler.getRandomKey();

		/** 2.获取执行ansible命令所需要的主机信息，以及docker镜像信息. **/
		String sshUser = getMcsSSHInfo(McsConstants.SSH_USER_CODE);
		String sshUserPwd = getMcsSSHInfo(McsConstants.SSH_USER_PWD_CODE);
		IpaasImageResource redisImage = getMcsImage(McsConstants.SERVICE_CODE, McsConstants.REDIS_IMAGE_CODE);

		/** 3.创建 mcs_host.cfg 文件，并写入hostIp. **/
		addHostFile(basePath, hostIp);
		logger.info("-----创建 mcs_host.cfg 成功！");

		/** 4.上传 ansible的 playbook 文件. **/
		uploadMcsFile(McsConstants.PLAYBOOK_MCS_PATH, McsConstants.PLAYBOOK_SINGLE_YML);
		logger.info("-----上传 mcs_single.yml 成功！");

		/** 5.生成ansible-playbook命令,并执行. **/
		String ansibleCommand = getRedisServerCommand(capacity, basePath, hostIp, cachePort, 
				requirepass, McsConstants.MODE_SINGLE, sshUser, sshUserPwd, redisImage);
		runAnsileCommand(ansibleCommand);
		logger.info("-----执行ansible-playbook 成功！");

		/** 7.处理zk配置. **/
		List<String> hostList = new ArrayList<String>();
		hostList.add(hostIp + ":" + cachePort);
		addCcsConfig(userId, serviceId, hostList, requirepass);
		logger.info("----------处理zk 配置成功！");

		/** 8.添加mcs用户实例信息. **/
		addUserInstance(userId, serviceId, capacity, hostIp, cachePort, requirepass, serviceName);
		logger.info("---------记录用户实例成功！");

		return McsConstants.SUCCESS_FLAG;
	}

	/**
	 * 开通集群模式的Mcs服务。
	 * @param paraMap
	 * @return
	 * @throws Exception
	 */
	private String openClusterMcs(Map<String, String> paraMap) throws PaasException {
		String userId = paraMap.get(McsConstants.USER_ID);
		String serviceId = paraMap.get(McsConstants.SERVICE_ID);
		String serviceName = paraMap.get(McsConstants.SERVICE_NAME);
		String capacity = paraMap.get(McsConstants.CAPACITY);
		Integer cacheSize = Integer.parseInt(capacity);
		final String basePath = AgentUtil.getAgentFilePath(AidUtil.getAid());
		final int clusterCacheSize = Math.round(cacheSize / McsConstants.CACHE_NUM * 2);
		
		/** 获取执行ansible命令所需要的主机信息，以及docker镜像信息. **/
		final String sshUser = getMcsSSHInfo(McsConstants.SSH_USER_CODE);
		final String sshUserPwd = getMcsSSHInfo(McsConstants.SSH_USER_PWD_CODE);
		final IpaasImageResource redisImage = getMcsImage(McsConstants.SERVICE_CODE, McsConstants.REDIS_IMAGE_CODE);
		final IpaasImageResource redisClusterImage = getMcsImage(McsConstants.SERVICE_CODE, McsConstants.REDIS_CLUSTER_IMAGE_CODE);
		
		/** 上传 ansible的 playbook 文件:mcs_single.yml, mcs_cluster.yml **/
		uploadMcsFile(McsConstants.PLAYBOOK_MCS_PATH, McsConstants.PLAYBOOK_SINGLE_YML);
		logger.info("-----上传 mcs_single.yml 成功！");
		uploadMcsFile(McsConstants.PLAYBOOK_MCS_PATH, McsConstants.PLAYBOOK_CLUSTER_YML);
		logger.info("-----上传 mcs_cluster.yml 成功！");

		/** 创建空的执行ansible-playbook所用host.cfg文件 **/
		createHostCfg(basePath);
		logger.info("-----创建 mcs_host.cfg 成功！");
		
		/** 从MCS资源池中选取资源  **/
		List<McsProcessInfo> cacheInfoList = selectMcsResCluster(clusterCacheSize, McsConstants.CACHE_NUM);
		logger.info("-----已获取开通redis集群所需资源主机["+cacheInfoList.size() +"]台。");
		
		/** 循环处理redis-cluster服务节点。 **/
		for (McsProcessInfo proInfo : cacheInfoList) {
			final String hostIp = proInfo.getCacheHostIp();
			final Integer cachePort = proInfo.getCachePort();
			final String requirepass = mcsSvHepler.getRandomKey();
			try{
				new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                        	/** 将hostIp写入 mcs_host.cfg 文件. **/
                        	writeHostCfg(basePath, hostIp);
                			logger.info("-----["+hostIp+"]写入 mcs_host.cfg 成功！");

                			/** 执行ansible-playbook命令. **/
                			String redisRun = getRedisServerCommand(clusterCacheSize+"", basePath, hostIp, cachePort, 
                					requirepass, McsConstants.MODE_CLUSTER, sshUser, sshUserPwd, redisImage);
                			runAnsileCommand(redisRun);
                			logger.info("-----执行ansible-playbook 成功！");
                        } catch (Exception e) {
                        	logger.error("start redis server error!"+e.getMessage());
                            e.printStackTrace();  
                        }
                    }
                }).start();
			} catch (Exception ex) {
				logger.error("start redis-cluster error!"+ex.getMessage());
				ex.printStackTrace();
				throw new PaasException("start redis server error.");
			}
		}

		/** 执行redis集群创建命令 **/
		String clusterInfo = getClusterInfo(cacheInfoList, " ");
		String redisClusterRun = getCreateClusterCommand(basePath, sshUser, sshUserPwd, clusterInfo, redisClusterImage);
		runAnsileCommand(redisClusterRun);
		logger.info("-------- 开通MCS集群模式，执行集群创建命令成功！");
		
		/** 添加zk配置 **/
		addZKConfig(userId, serviceId, cacheInfoList);
		logger.info("-------- 开通MCS集群模式，处理zk 配置成功！");
		
		/** 记录用户的MCS开通实例信息 **/
		addMcsUserInstance(userId, serviceId, serviceName, clusterCacheSize, cacheInfoList);
		logger.info("-------- 开通MCS集群模式，记录用户的MCS开通实例信息成功！");
		
		return McsConstants.SUCCESS_FLAG;
	}

	/**
	 * 开通主备模式的Mcs服务。
	 * 在同一台资源主机上，先启动一个single模式的redis，再增加一个slave节点.
	 * slave节点的port值，是master节点port值－1.
	 * @param paraMap
	 * @return
	 * @throws PaasException
	 */
	private String openReplicationMcs(Map<String, String> paraMap) throws PaasException {
		String userId = paraMap.get(McsConstants.USER_ID);
		String serviceId = paraMap.get(McsConstants.SERVICE_ID);
		String serviceName = paraMap.get(McsConstants.SERVICE_NAME);
		String capacity = paraMap.get(McsConstants.CAPACITY);
		Integer cacheSize = Integer.parseInt(capacity);
		String basePath = AgentUtil.getAgentFilePath(AidUtil.getAid());

		/** 1.获取mcs资源. **/
		McsResourcePool mcsResourcePool = selectMcsResSingle(cacheSize * 2, 2);
		String hostIp = mcsResourcePool.getCacheHostIp();
		Integer masterPort = mcsResourcePool.getCachePort();
		String masterPwd = mcsSvHepler.getRandomKey();
		logger.info("-----所选资源主机[" + hostIp + ":" + masterPort + "], masterPwd:" + masterPwd);

		/** 2.获取执行ansible命令所需要的主机信息，以及docker镜像信息. **/
		String sshUser = getMcsSSHInfo(McsConstants.SSH_USER_CODE);
		String sshUserPwd = getMcsSSHInfo(McsConstants.SSH_USER_PWD_CODE);
		IpaasImageResource redisImage = getMcsImage(McsConstants.SERVICE_CODE, McsConstants.REDIS_IMAGE_CODE);

		/** 3.创建 mcs_host.cfg 文件，并写入hostIp. **/
		addHostFile(basePath, hostIp);
		logger.info("-----创建 mcs_host.cfg 成功！");

		/** 4.上传 ansible的 playbook 文件. **/
		uploadMcsFile(McsConstants.PLAYBOOK_MCS_PATH, McsConstants.PLAYBOOK_SINGLE_YML);
		logger.info("-----上传 mcs_single.yml 成功！");
		uploadMcsFile(McsConstants.PLAYBOOK_MCS_PATH, McsConstants.PLAYBOOK_REPLICATION_YML);
		logger.info("-----上传 mcs_replication.yml 成功！");

		/** 5.生成创建master节点的命令,并执行. **/
		String ansibleCommand = getRedisServerCommand(capacity, basePath, hostIp, masterPort, 
				masterPwd, McsConstants.MODE_SINGLE, sshUser, sshUserPwd, redisImage);
		runAnsileCommand(ansibleCommand);
		logger.info("-----执行ansible-playbook 成功！");
		
		/** 6.生成创建slave节点的命令,并执行. **/
		Integer slavePort = masterPort -1;
		String slaveCommand = getRedisSlaveCommand(capacity, basePath, hostIp, slavePort, 
				masterPwd, McsConstants.MODE_REPLICATION, sshUser, sshUserPwd, hostIp, masterPort, redisImage);
		runAnsileCommand(slaveCommand);
		logger.info("-----执行slaveCommand 成功！");
		
		/** 7.处理zk配置. **/
		List<String> hostList = new ArrayList<String>();
		hostList.add(hostIp + ":" + masterPort);
		addCcsConfig(userId, serviceId, hostList, masterPwd);
		logger.info("----------处理zk 配置成功！");

		/** 8.添加mcs用户实例信息. **/
		addUserInstance(userId, serviceId, capacity, hostIp, masterPort, masterPwd, serviceName);
		logger.info("---------记录用户实例成功！");
		
		return McsConstants.SUCCESS_FLAG;
	}
	
	private void openSentinelMcs(Map<String, String> paraMap) throws PaasException {
	}
	
	private void runAnsileCommand(String ansibleCommand) throws PaasException {
		try {
			AgentUtil.executeCommand(ansibleCommand, AidUtil.getAid());
		} catch (Exception ex) {
			logger.error("Excute runAnsileCommand() error, command is " + ansibleCommand);
			ex.printStackTrace();
			throw new PaasException("runAnsileCommand() exception.");
		}
	}

	private String getRedisServerCommand(String capacity, String basePath,
			String hostIp, Integer cachePort, String requirepass, String mode,
			String sshUser, String sshUserPwd, IpaasImageResource mcsImage) {
		StringBuilder ansibleCommand = new StringBuilder("/usr/bin/ansible-playbook -i ")
			.append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
			.append(McsConstants.PLAYBOOK_HOST_CFG).append(" ")
			.append(basePath).append("/mcs/").append(McsConstants.PLAYBOOK_SINGLE_YML)
			.append(" --user=").append(sshUser)
			.append(" --extra-vars \"ansible_ssh_pass=").append(sshUserPwd)
			.append(" image=").append(mcsImage.getImageRepository()).append("/").append(mcsImage.getImageName())
			.append(" REDIS_PORT=").append(cachePort)
			.append(" START_MODE=").append(mode)
			.append(" host=").append(hostIp)
			.append(" user=").append(sshUser)
			.append(" MAX_MEM=").append(capacity).append("m")
			.append(" PASSWORD=").append(requirepass).append("\"");
		logger.info("-----ansibleCommand:" + ansibleCommand.toString());
		return ansibleCommand.toString();
	}
	
	private String getCreateClusterCommand(String basePath, String sshUser, String sshUserPwd, 
			String clusterInfo, IpaasImageResource mcsImage) {
		StringBuilder commond = new StringBuilder("/usr/bin/ansible-playbook -i ")
			.append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
			.append(McsConstants.PLAYBOOK_HOST_CFG).append(" ")
			.append(basePath).append("/mcs/").append(McsConstants.PLAYBOOK_CLUSTER_YML)
			.append(" --user=").append(sshUser)
			.append(" --extra-vars \"ansible_ssh_pass=").append(sshUserPwd)
			.append(" image=").append(mcsImage.getImageRepository()).append("/").append(mcsImage.getImageName())
			.append(" CLUSTER_INFO=").append(clusterInfo).append("\"");
		logger.info("-----createClusterCommand:" + commond.toString());
		return commond.toString();
	}
	
	private String getRedisSlaveCommand(String capacity, String basePath,
			String hostIp, Integer cachePort, String masterpass, String mode,
			String sshUser, String sshUserPwd, String masterIp, Integer masterPort, IpaasImageResource mcsImage) {
		StringBuilder ansibleCommand = new StringBuilder("/usr/bin/ansible-playbook -i ")
			.append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
			.append(McsConstants.PLAYBOOK_HOST_CFG).append(" ")
			.append(basePath).append("/mcs/").append(McsConstants.PLAYBOOK_REPLICATION_YML)
			.append(" --user=").append(sshUser)
			.append(" --extra-vars \"ansible_ssh_pass=").append(sshUserPwd)
			.append(" image=").append(mcsImage.getImageRepository()).append("/").append(mcsImage.getImageName())
			.append(" REDIS_PORT=").append(cachePort)
			.append(" START_MODE=").append(mode)
			.append(" host=").append(hostIp)
			.append(" user=").append(sshUser)
			.append(" MAX_MEM=").append(capacity).append("m")
			.append(" PASSWORD=").append(masterpass)
			.append(" MASTER_IP=").append(masterIp)
			.append(" MASTER_PORT=").append(masterPort).append("\"");
		logger.info("-----ansibleCommand:" + ansibleCommand.toString());
		return ansibleCommand.toString();
	}
	
	private String getMcsSSHInfo(String field_code) throws PaasException {
		IpaasSysConfigMapper sysconfigDao = ServiceUtil.getMapper(IpaasSysConfigMapper.class);
		IpaasSysConfigCriteria rpmc = new IpaasSysConfigCriteria();
		rpmc.createCriteria().andTableCodeEqualTo(McsConstants.SERVICE_CODE).andFieldCodeEqualTo(field_code);
		List<IpaasSysConfig> res = sysconfigDao.selectByExample(rpmc);
		if (res == null || res.isEmpty())
			throw new PaasException("MCS ssh user not config.");
		return res.get(0).getFieldValue();
	}
	
	private IpaasImageResource getMcsImage(String serviceCode, String imageCode) throws PaasException {
		IpaasImageResourceMapper rpm = ServiceUtil.getMapper(IpaasImageResourceMapper.class);
		IpaasImageResourceCriteria rpmc = new IpaasImageResourceCriteria();
		rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS)
				.andServiceCodeEqualTo(serviceCode).andImageCodeEqualTo(imageCode);
		List<IpaasImageResource> res = rpm.selectByExample(rpmc);
		if (res == null || res.isEmpty())
			throw new PaasException("MCS IMAGE not config.");
		return res.get(0);
	}
	
	private void createHostCfg(String basePath) throws PaasException {
		StringBuilder command = new StringBuilder();
		command.append(" mkdir -p ").append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
		.append(" &&cd ").append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
		.append(" &&touch ").append(McsConstants.PLAYBOOK_HOST_CFG);
		
		runAnsileCommand(command.toString());
	}
	
	private void writeHostCfg(String basePath, String hostIp) throws PaasException {
		StringBuilder command = new StringBuilder();
		command.append("cd ").append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
		.append(" &&echo ").append(hostIp).append("> ").append(McsConstants.PLAYBOOK_HOST_CFG);

		runAnsileCommand(command.toString());
	}
	
	private void addHostFile(String basePath, String hostIp) throws PaasException {
		StringBuilder command = new StringBuilder();
		command.append(" mkdir -p ").append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
		.append(" &&cd ").append(basePath).append(McsConstants.PLAYBOOK_CFG_PATH)
		.append(" &&touch ").append(McsConstants.PLAYBOOK_HOST_CFG)
		.append(" &&echo ").append(hostIp).append("> ").append(McsConstants.PLAYBOOK_HOST_CFG);
		
		runAnsileCommand(command.toString());
	}
	
	/** 
	 * 上传文件
	 ***/
	private void uploadMcsFile(String destPath, String fileName) throws PaasException {
		InputStream in = McsManageImpl.class.getResourceAsStream(destPath + fileName);
		try{
			AgentUtil.uploadFile("mcs/"+fileName, AgentUtil.readFileLines(in), AidUtil.getAid());
			in.close();
		} catch (Exception ex) {
			logger.error("Excute uploadMcsFiles() failed," + ex.getMessage());
			ex.printStackTrace();
			throw new PaasException("openSingleMcs.uploadMcsFile() exception.");
		}
	}

	private void addMcsUserInstance(String userId, String serviceId, String serviceName, 
			final int clusterCacheSize, List<McsProcessInfo> cacheInfoList) throws PaasException {
		for (McsProcessInfo cacheInfo : cacheInfoList) {
			logger.info("----add mcs_user_Instance---- userId:["+userId+"],serviceId:["+serviceId+"],"
					+ "clusterCacheSize:["+clusterCacheSize+",cacheIp["+cacheInfo.getCacheHostIp()+"],"
						+ "cachePort:["+cacheInfo.getCachePort()+"],serviceName:["+serviceName+"]");
			addUserInstance(userId, serviceId, clusterCacheSize + "", cacheInfo.getCacheHostIp(), 
					cacheInfo.getCachePort(), null, serviceName);
		}
	}

	private void addZKConfig(String userId, String serviceId,
			List<McsProcessInfo> cacheInfoList) throws PaasException {
		String redisCluster4ZK = getClusterInfo(cacheInfoList, ";");
		logger.info("-------- redisCluster4ZK is :" + redisCluster4ZK);
		List<String> hostList = new ArrayList<String>();
		hostList.add(redisCluster4ZK.substring(1));
		addCcsConfig(userId, serviceId, hostList, null);
	}
	
	/**
	 * 允许修改容量、描述，serviceId userId capacity 都不能为空
	 * @throws PaasException
	 */
	@Override
	public String modifyMcs(String param) throws PaasException {
		Map<String, String> map = McsParamUtil.getParamMap(param);
		String serviceId = map.get(McsConstants.SERVICE_ID);
		String serviceName = map.get(McsConstants.SERVICE_NAME);
	    String userId = map.get(McsConstants.USER_ID);
		String capacity = map.get(McsConstants.CAPACITY);
		
		Assert.notNull(serviceId, "serviceId为空");
		Assert.notNull(userId, "userId为空");
		Assert.notNull(capacity, "capacity为空");
		
		logger.info("M1----------------获得已申请过的记录");
		List<McsUserCacheInstance> userInstanceList = mcsSvHepler.getMcsUserCacheInstances(serviceId, userId);
		if (userInstanceList == null || userInstanceList.isEmpty()) {
			throw new PaasException("UserId["+userId+"]的["+serviceId+"]服务未开通过，无法修改！");
		}
		
		/** 计算扩容的容量大小。 **/
		logger.info("M2----------------修改mcs_resource");  
		int cacheSize = userInstanceList.size() == 1 ? Integer.valueOf(capacity)
				: Math.round(Integer.valueOf(capacity) / McsConstants.CACHE_NUM * 2);
		modifyMcsResource(userInstanceList, true, cacheSize);
		
		logger.info("M3----------------修改mcs服务端，重启redis");
		modifyMcsServerFileAndUserIns(userId, serviceId, userInstanceList, cacheSize, serviceName);
		
		logger.info("----------------修改成功");
		
		return McsConstants.SUCCESS_FLAG;
	}
	
	/**
	 * 门户管理控制台功能：启动MCS
	 */
	@Override
	public String startMcs(String param) throws PaasException {
		Map<String, String> map = McsParamUtil.getParamMap(param);
		String serviceId = map.get(McsConstants.SERVICE_ID);
		String userId = map.get(McsConstants.USER_ID);
		
		/** 根据user_id,service_id,获取用户实例信息 **/
		List<McsUserCacheInstance> userInstanceList = mcsSvHepler.getMcsUserCacheInstances(serviceId, userId);
		if (userInstanceList == null || userInstanceList.isEmpty()) {
			throw new PaasException("UserId["+userId+"]的["+serviceId+"]服务未开通过，无法启动！");
		}
		
		/** 调用启动Mcs的处理方法 **/
		startMcs(userId, serviceId, userInstanceList);
		logger.info("----------启动["+userId+"]-["+serviceId+"]的MCS缓存服务,成功");
		
		return McsConstants.SUCCESS_FLAG;
	}

	/**
	 * 门户管理控制台功能：停止Mcs服务
	 */
	@Override
	public String stopMcs(String param) throws PaasException {
		Map<String, String> map = McsParamUtil.getParamMap(param);
		String serviceId = map.get(McsConstants.SERVICE_ID);
		String userId = map.get(McsConstants.USER_ID);
		
		/** 根据user_id,service_id,获取用户实例信息 **/
		List<McsUserCacheInstance> userInstanceList = mcsSvHepler.getMcsUserCacheInstances(serviceId, userId);
		if (userInstanceList == null || userInstanceList.isEmpty()) {
			throw new PaasException("UserId["+userId+"]的["+serviceId+"]服务未开通过，无法停止！");
		}
		
		stopMcsInsForCacheIns(userInstanceList);
		logger.info("----------停止["+userId+"]-["+serviceId+"]的MCS缓存服务,成功");
		
		return McsConstants.SUCCESS_FLAG;
	}
	
	/**
	 * 门户管理控制台功能：重启MCS服务。
	 */
	@Override
	public String restartMcs(String param) throws PaasException {
		Map<String, String> map = McsParamUtil.getParamMap(param);
		String serviceId = map.get(McsConstants.SERVICE_ID);
		String userId = map.get(McsConstants.USER_ID);
		
		/** 根据user_id,service_id,获取用户实例信息 **/
		List<McsUserCacheInstance> userInstanceList = mcsSvHepler.getMcsUserCacheInstances(serviceId, userId);
		if (userInstanceList == null || userInstanceList.isEmpty()){
			throw new PaasException("UserId["+userId+"]的["+serviceId+"]服务未开通过，无法重启！");
		}
		
		logger.info("------- 停止["+userId+"]-["+serviceId+"]的MCS缓存服务");
		stopMcsInsForCacheIns(userInstanceList);
		
		logger.info("------- 启动["+userId+"]-["+serviceId+"]的MCS缓存服务");
		startMcs(userId, serviceId, userInstanceList);
		
		logger.info("------- 重启["+userId+"]-["+serviceId+"]的MCS缓存服务OK.");
		
		return McsConstants.SUCCESS_FLAG;
	}

	/**
	 * 门户管理控制台功能：注销MCS服务
	 */
	@Override
	public String cancelMcs(String param) throws PaasException {
		Map<String, String> map = McsParamUtil.getParamMap(param);
		String serviceId = map.get(McsConstants.SERVICE_ID);
		String userId = map.get(McsConstants.USER_ID);
	
		/** 根据user_id,service_id,获取用户实例信息 **/
		List<McsUserCacheInstance> userInstanceList = mcsSvHepler.getMcsUserCacheInstances(serviceId, userId);
		if (userInstanceList == null || userInstanceList.isEmpty()) {
			throw new PaasException("UserId["+userId+"]的["+serviceId+"]服务未开通过，无法注销！");
		}
		
		logger.info("------- 注销["+userId+"]-["+serviceId+"]的MCS缓存服务,更新MCS资源表中已使用缓存的大小.");
		modifyMcsResource(userInstanceList, false, 0);
		
		logger.info("------- 注销["+userId+"]-["+serviceId+"]的MCS缓存服务,删除配置文件.");
		removeMcsServerFileAndUserIns(userId, serviceId, userInstanceList);

		logger.info("------- 注销["+userId+"]-["+serviceId+"]的MCS缓存服务,OK!");
		
		return McsConstants.SUCCESS_FLAG;
	}

	/**
	 * 服务是否已经存在
	 * 
	 * @param userId
	 * @param serviceId
	 * @return
	 */
	private boolean existsService(String userId, String serviceId) {
		McsUserCacheInstanceCriteria cc = new McsUserCacheInstanceCriteria();
		cc.createCriteria().andUserIdEqualTo(userId).andSerialNumberEqualTo(serviceId)
			.andStatusEqualTo(McsConstants.VALIDATE_STATUS);
		McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
		return im.countByExample(cc) > 0;
	}

	/**
	 * 集群模式时，新增服务端的配置文件
	 * @param cacheInfoList
	 * @param userId
	 * @param serviceId
	 * @param cacheSize
	 * @return
	 * @throws PaasException
	 */
	private void addMcsConfigCluster(List<McsProcessInfo> cacheInfoList,
			String userId, String serviceId, int cacheSize)throws PaasException {
		
		logger.info("-------- 开始处理集群配置文件的time：" + new Date());
		for (McsProcessInfo proInfo : cacheInfoList) {
			String cacheHost = proInfo.getCacheHostIp();
			String cachePath = proInfo.getCachePath();
			Integer cachePort = proInfo.getCachePort();
			Integer agentPort = proInfo.getAgentPort();
			String clusterPath = cachePath + McsConstants.CLUSTER_FILE_PATH;
			String logPath = cachePath + McsConstants.LOG_PATH;
			
			/** 初始化Agentclint **/
			AgentClient ac = new AgentClient(cacheHost, agentPort);

			/** 创建redis集群的目录: ./redis/cluster/user_id+service_id/ **/
			String mkdir_cluster = "mkdir -p " + clusterPath + userId + "_" + serviceId;
			ac.executeInstruction(mkdir_cluster);
			logger.info("-------- 创建redis集群的目录：" + mkdir_cluster +", OK!");
			
			/**
			 * 创建redis集群下的server目录: ./redis/cluster/user_id+service_id/redisPort/
			 **/
			String mkdir_port = "mkdir -p " + clusterPath + userId + "_" + serviceId + "/" + cachePort;
			ac.executeInstruction(mkdir_port);
			logger.info("-------- 创建redis集群下的server目录：" + mkdir_port +", OK!");
			
			/** 生成集群中server的配置文件，文件名要有全路径。 **/
			String configFile = clusterPath + userId + "_" + serviceId + "/" + cachePort + "/" + "redis-" + cachePort + ".conf";
			String configDetail = "include " + clusterPath
					+ "redis-common.conf \n" + "pidfile " + clusterPath + "redis-"
					+ cachePort + ".pid" + "\n" + "port " + cachePort + "\n"
					+ "cluster-enabled yes \n" + "maxmemory " + cacheSize
					+ "m \n" + "cluster-config-file nodes.conf \n"
					+ "cluster-node-timeout 5000 \n" + "appendonly no \n";

			/** 上传配置文件 **/
			ac.saveFile(configFile, configDetail);
			logger.info("-------- 上传redis集群的配置文件：" + configFile +", OK!");

			/** 启动集群中的每个redis-server **/
			String cmd_path = clusterPath + userId + "_" + serviceId + "/" + cachePort + "/";
			String cmd_start = "redis-server " + configFile + " > " + logPath + "redis-" + cachePort + ".log &";
			String result = ac.executeInstruction(cmd_path, cmd_start);
			logger.info("-------- 启动redis集群的server：" + cmd_start + ", result=["+result+"], OK!");
			
			logger.info("-------- 处理MCS_CLUSTER中的server["+cacheHost+":"+cachePort+"]结束, time:" + new Date());
		}
	}

	/**
	 * 生成"ip1:port1;ip2:port2"格式的集群信息串
	 * @param list
	 * @param separator
	 * @return
	 * @throws PaasException
	 */
	private String getClusterInfo(List<McsProcessInfo> list, String separator) throws PaasException {
		String cluster = "";
		for(McsProcessInfo vo: list) {
			cluster += vo.getCacheHostIp() + ":" + vo.getCachePort() + separator;
		}
		return cluster;
	}
	
	/**
	 * 在开通MCS服务的集群(cluster)模式/主从切换(sentinel)模式时，选择MCS资源。
	 * @param cacheSize  需要开通的每个实例的缓存大小
	 * @param redisInsNum  启动的redis实例数
	 * @return List<McsProcessInfo>
	 * @throws PaasException
	 */
	//TODO:需要重构逻辑
	private List<McsProcessInfo> selectMcsResCluster(int cacheSize, int redisInsNum) throws PaasException {
		/** defined return result. **/
		List<McsProcessInfo> cacheInfoList = new ArrayList<McsProcessInfo>();
		
		/** 如果资源主机数量少于redisInsNum，可能在一个资源主机上启动多个实例。 **/
		List<McsResourcePool> resourceList = getBestResource(redisInsNum);
		int hostNum = resourceList.size();   /** 一般资源池中所配主机为3台，会获取3条资源记录。 **/
		
		/** 已获取到的实例数量 **/
		int gotInsNum = 0;
		
		/** 资源主机数量 **/
		int k = hostNum;
		
		/** 记录while循环的次数. **/
		int loopCount = 0;
		
		int j = 1;
		
		String hostIp = null;
		int port = -1;
		
		/** 如果已选定的资源数量，小于申请的数量; 并且，while循环的次数，小于申请的数量+1，则继续资源选择处理. **/
		while (gotInsNum < redisInsNum && loopCount < (redisInsNum + 1)) {
			for (int m = 0; m < k; m++) {
				McsResourcePool pool = resourceList.get(m);
				hostIp = pool.getCacheHostIp();
				port = pool.getCachePort() + 1;
				
				/** 当前所选资源主机的已占用的内存大小 **/
				Integer usedCacheSize = pool.getCacheMemoryUsed();
				
				/** 资源主机的总内存大小 **/
				Integer cacheMemory = pool.getCacheMemory();
				
				/** 当前所选资源主机的可用内存，小于所申请的内存，则不再使用该资源，跳出循环。 **/
				if ((cacheMemory - usedCacheSize) < cacheSize * j) {
					k = m;     /** ??? **/
					j++;  	   /** 当前主机内存不够，下个资源主机则需承担此容量，下一次判断开通总容量需 *j，所以需要加权。 **/
					continue;  /** 跳出本次资源主机的选择逻辑 **/
				}
				
				/** cycle＝1, 表示端口循环使用 **/
				if (pool.getCycle() == 1) {
					/** 从MCS用户实例表中，查找一条失效状态的记录，使用此实例的端口 **/
					pool.setCachePort(getCanUseInstance(hostIp).getCachePort());
				} else {
					pool.setCachePort(port);
					pool.setCacheMemoryUsed(usedCacheSize + cacheSize);
					if (pool.getCachePort() == pool.getMaxPort()) {
						pool.setCycle(1);
					}
					int changeRow = updateResource(pool);
					logger.info("---- 选定的资源信息：id:["+pool.getId()+"],port:["+pool.getCachePort()+"]. ----");
					logger.info("---- selectMcsResCluster()中，选定资源后，更新了["+changeRow+"]条资源记录 ----");
					if (changeRow != 1) {
						logger.error("---- selectMcsResCluster()中，选定资源后，更新资源失败！----");
						throw new PaasException("---- selectMcsResCluster()中，选定资源后，更新资源失败！----");
					}
				}
				
				logger.info("--------selectMcsResCluster(), 从主机:" + hostIp + "中，选定了端口：" + port);
				 
				/** 将所选定的主机、端口等信息，放到list返回值中，开通时遍历使用。 **/
				String cachePath = pool.getCachePath();
				Integer agentPort = Integer.parseInt(pool.getAgentCmd());
				
				McsProcessInfo vo = new McsProcessInfo();
				vo.setCacheHostIp(hostIp);
				vo.setCachePath(cachePath);
				vo.setCachePort(port);
				vo.setAgentPort(agentPort);
				cacheInfoList.add(vo);
				
				gotInsNum++;
			}
			
			loopCount++;
		}
		
		logger.info("----- selectMcsResCluster():申请["+redisInsNum+"]个实例，选定了["+gotInsNum+"]个实例 -----");
		
//		if (loopCount > redisInsNum) {
		/** 如果循环count次后，已选定的实例数量仍小于申请的数量，则表示资源不足 **/
		if (gotInsNum < redisInsNum) {
			logger.error("++++ 资源不足:申请开通["+redisInsNum+"]个size为["+cacheSize+"]实例，目前只选择了["+gotInsNum+"]个实例 ++++");
			throw new PaasException("mcs resource not enough. ");
		}
		
		return cacheInfoList;
	}
	
	/**
	 * 在开通MCS服务的集群(single)模式/主从复制(replication)模式时，选择MCS资源。
	 * 单例模式端口+1, 主从复制模式端口+2
	 * @param cacheSize
	 * @param portOffset
	 * @return McsResourcePool
	 * @throws PaasException
	 */
	private McsResourcePool selectMcsResSingle(int cacheSize, int portOffset) throws PaasException {
		List<McsResourcePool> resp = getBestResource(1);
		McsResourcePool mcsResourcePool = resp.get(0);
		
		/** 如果该主机端口已经用完，从mcs_user_cache_instance选择该主机最小的已经失效的端口号  **/
		if (mcsResourcePool != null && mcsResourcePool.getCycle() == 1) {
			mcsResourcePool.setCachePort(getCanUseInstance(mcsResourcePool.getCacheHostIp()).getCachePort());
		} else {
			if (mcsResourcePool.getCachePort() == mcsResourcePool.getMaxPort()) {
				mcsResourcePool.setCycle(1);
			}
			/** 从入参获取端口偏移量，开通单例模式端口+1, 主从复制模式端口+2。 **/
			mcsResourcePool.setCachePort(mcsResourcePool.getCachePort() + portOffset);
			mcsResourcePool.setCacheMemoryUsed(mcsResourcePool.getCacheMemoryUsed() + cacheSize);
			int changeRow = updateResource(mcsResourcePool,  portOffset);

			if (changeRow != 1) {
				throw new PaasException("更新资源失败");
			}
		}
		
		return mcsResourcePool;
	}
	
	/**
	 * 新增用户的缓存实例
	 * 
	 * @param userId
	 * @param serviceId
	 * @param capacity
	 * @param mcsResourcePool
	 * @throws PaasException
	 */
	private void addUserInstance(String userId, String serviceId,
			String capacity, String ip, int port, String pwd, String serviceName) throws PaasException {
		McsUserCacheInstance bean = new McsUserCacheInstance();
		bean.setUserId(userId);
		bean.setCacheHost(ip);
		bean.setCacheMemory(Integer.valueOf(capacity));
		bean.setStatus(McsConstants.VALIDATE_STATUS);
		bean.setBeginTime(DateTimeUtil.getNowTimeStamp());
		bean.setEndTime(DateTimeUtil.getNowTimeStamp());
		bean.setSerialNumber(serviceId);
		bean.setCachePort(port);
		bean.setPwd(pwd);
		bean.setServiceName(serviceName);

		ServiceUtil.getMapper(McsUserCacheInstanceMapper.class).insert(bean);
	}

	/**
	 * 在zk中记录申请信息
	 * 
	 * @param userId
	 * @param serviceId
	 * @param mcsResourcePool
	 * @param requirepass
	 * @throws PaasException
	 */
	private void addCcsConfig(String userId, String serviceId, List<String> hosts, String requirepass) throws PaasException {
		CCSComponentOperationParam op = new CCSComponentOperationParam();
		op.setUserId(userId);
		op.setPath(McsConstants.MCS_ZK_PATH + serviceId);
		op.setPathType(PathType.READONLY);

		JsonObject dataJson = new JsonObject();
		for(String host: hosts) {
			dataJson.addProperty("hosts", host);
		}
		
		logger.info("------ Mcs服务需要在 zk中记录的信息 ------");
		logger.info("------ userId:["+userId+"] ------");
		logger.info("------ zkPath:["+McsConstants.MCS_ZK_PATH + serviceId+"]");
		logger.info("------ dataJson[hosts]:" + dataJson.toString());
		logger.info("------ dataJson[password]:" + requirepass);
		
		if (requirepass != null && requirepass.length() > 0) {
			logger.info("------ CiperUtil.encrypt(password):" + CiperUtil.encrypt(McsConstants.PWD_KEY, requirepass));
			dataJson.addProperty("password", CiperUtil.encrypt(McsConstants.PWD_KEY, requirepass));
		}
		
		iCCSComponentManageSv.add(op, dataJson.toString());
		logger.info("------ 在zk的"+McsConstants.MCS_ZK_PATH + serviceId+"路径下，记录MCS申请信息成功!");

		op.setPath(McsConstants.MCS_ZK_COMMON_PATH);
		if (!iCCSComponentManageSv.exists(op)) {
			logger.info("------ 设置zk中的 /MCS/COMMON 路径 ------");
			iCCSComponentManageSv.add(op, McsConstants.MCS_ZK_COMMON);
			logger.info("------ 在zk中记录COMMON信息成功!");
		}
	}

	/**
	 * 上传配置文件，执行命令
	 * 
	 * @param mcsResourcePool
	 * @param capacity
	 * @param requirepass
	 * @throws PaasException
	 */
	private void addMcsConfig(AgentClient ac, String cachePath, int redisPort, String capacity, String requirepass) 
			throws PaasException {
		try {
			String fileName = cachePath + McsConstants.FILE_PATH + redisPort + "/" + "redis-" + redisPort + ".conf";
			String configDetail = "include " + cachePath + McsConstants.FILE_PATH + "redis-common.conf" + "\n"
					+ "pidfile /var/run/redis-" + redisPort + ".pid" + "\n"
					+ "port " + redisPort + "\n"
					+ "maxmemory " + capacity + "m" + "\n"
					+ "requirepass " + requirepass + "\n"
					+ "logfile " + cachePath + "/redis/log/redis-" + redisPort + ".log";
			
			ac.saveFile(fileName, configDetail);
			logger.info("---------新生成的redis配置文件，上传成功!");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("单例 上传文件失败：" + e.getMessage(), e);
		}
	}

	/**
	 * 停redis，修改mcs服务端的配置文件，修改用户实例，启动redis
	 * 
	 * @param userInstanceList
	 * @param cacheSize
	 * @throws PaasException
	 */
	//TODO: 与 removeMcsServerFileAndUserIns() 方法类似，需重构。
	private void modifyMcsServerFileAndUserIns(final String userId,
			final String serviceId, List<McsUserCacheInstance> userInstanceList,
			final int cacheSize, String serviceName) throws PaasException {
		
		if (userInstanceList.size() == 1) {
			McsUserCacheInstance tempIns = userInstanceList.get(0);
			McsResourcePoolMapper mapper = ServiceUtil.getMapper(McsResourcePoolMapper.class);
			McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
			rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(tempIns.getCacheHost());
			
			List<McsResourcePool> pools = mapper.selectByExample(rpmc);
			McsResourcePool pool = pools.get(0);

			String cachePath = pool.getCachePath();
			Integer agentPort = Integer.parseInt(pool.getAgentCmd());
			
			String cacheHostIp = tempIns.getCacheHost();
			Integer cachePort = tempIns.getCachePort();
			String requirepass = tempIns.getPwd();
			String commonconfigPath = cachePath + McsConstants.FILE_PATH;
			
			logger.info("------------- redis info cacheHostIp:["+cacheHostIp+"]-----------");
			logger.info("------------- redis info cachePort:["+cachePort+"]---------------");
			logger.info("------------- redis info requirepass:["+requirepass+"]-----------");
			logger.info("------------- redis info agentPort:["+agentPort+"]---------------");
			logger.info("------------- redis info commonconfigPath:["+commonconfigPath+"]-");
			
			/** 1.获取agent客户端 **/
			AgentClient ac = new AgentClient(cacheHostIp, agentPort);
			
			/** 2.停redis  **/
			logger.info("----- stop redis -----");
			stopMcsIns(ac, cachePort);
			
			/** 3.修改redis的配置文件  **/
			logger.info("----- modify redis config -----requirepass：" + requirepass);
			addMcsConfig(ac, cachePath, cachePort, cacheSize + "", requirepass);

			/** 4.启动redis **/
			logger.info("----- start redis -----");
			startMcsIns(ac, commonconfigPath, cachePort);
			
			/** 5.组织待更新的数据 **/
			logger.info("------- cacheSize："+cacheSize);
			tempIns.setCacheMemory(cacheSize);
			if (serviceName != null && serviceName.length() > 0){
				tempIns.setServiceName(serviceName);
			}
			
			/** 6.更新用户实例表的“已使用的内存”的字段  **/
			logger.info("----- update cache size info -------");
			McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
			im.updateByPrimaryKey(tempIns);
			
		} else {  /** 处理集群模式的redis **/
			McsResourcePool pool = null;
			List<McsProcessInfo> cacheInfoList = new ArrayList<McsProcessInfo>();
			for(McsUserCacheInstance tempIns : userInstanceList){
				/** 获取mcs资源表中的cachePath、agentPort信息 **/
				McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
				McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
				rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(tempIns.getCacheHost());
				List<McsResourcePool> pools = rpm.selectByExample(rpmc);
				pool = pools.get(0);
				
				String cachePath = pool.getCachePath();
				Integer agentPort = Integer.parseInt(pool.getAgentCmd());
				String cacheHostIp = tempIns.getCacheHost();
				Integer cachePort = tempIns.getCachePort();
				
				/** 将集群配置文件、集群启动所需的信息，放到value中。 **/
				McsProcessInfo value = new McsProcessInfo();
				value.setCacheHostIp(cacheHostIp);
				value.setCachePort(cachePort);
				value.setAgentPort(agentPort);
				value.setCachePath(cachePath);
				cacheInfoList.add(value);
				
				logger.info("------- redis info cacheHostIp:["+cacheHostIp+"]---------");
				logger.info("------- redis info cachePort:["+cachePort+"]-------------");
				logger.info("------- redis info agentPort:["+agentPort+"]-------------");
				logger.info("------- redis info cachePath:["+cachePath+"]-------------");
				
				/** 获取agent客户端 **/
				AgentClient ac = new AgentClient(cacheHostIp, agentPort);
				
				/** 停redis  **/
				stopMcsIns(ac, cachePort);
				logger.info("----- stop cluster redis is successful-----");
				
				/** 组织待更新的数据 **/
				tempIns.setCacheMemory(cacheSize);
				if (serviceName != null && serviceName.length() > 0) {
					tempIns.setServiceName(serviceName);
				}
				
				/** 更新用户实例表 **/
				logger.info("----- update cache size info -------");
				McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
				im.updateByPrimaryKey(tempIns);
			}
			
			/** 处理redis集群中的每个server的目录、配置文件，并启动。 **/
			addMcsConfigCluster(cacheInfoList, userId, serviceId, cacheSize);
			
			/** 在集群中的任意台主机上，执行redis集群创建的命令 **/
			McsProcessInfo vo = cacheInfoList.get(0);
			AgentClient ac = new AgentClient(vo.getCacheHostIp(), vo.getAgentPort());
			
			/** 组织集群创建的命令及返回值 **/
			String clusterInfo = getClusterInfo(cacheInfoList, " ");
			String create_cluster = "redis-trib.rb create --replicas 1 " + clusterInfo;
			logger.info("-------- 创建redis集群的命令:" + create_cluster);
			
			/** 创建redis集群 **/
			ac.executeInstruction(vo.getCachePath()+McsConstants.CLUSTER_FILE_PATH, create_cluster);
			logger.info("-------- 创建redis集群成功 --------");
		}
	}

	/**
	 * 修改缓存资源池的使用内存量
	 * 
	 * @param userInstanceList
	 * @param cacheSize
	 */
	private void modifyMcsResource(List<McsUserCacheInstance> userInstanceList, Boolean isAdd, int cacheSize) {
		for(McsUserCacheInstance userInstance: userInstanceList) {
			String host = userInstance.getCacheHost();
			Integer curentCacheSize = userInstance.getCacheMemory();
			
			/** 获取当前用户实例的值对象 **/
			McsResourcePoolMapper mapper = ServiceUtil.getMapper(McsResourcePoolMapper.class);
			McsResourcePoolCriteria condition = new McsResourcePoolCriteria();
			condition.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(host);
			List<McsResourcePool> pools = mapper.selectByExample(condition);
			McsResourcePool pool = pools.get(0);
			
			/** 
			 * 根据操作类型“扩容”／“注销”，更新MCS资源表中的“已使用缓存容量”字段.
			 * 规则：“扩容”->“加值”；
			 * 		“注销”->“减值”。
			 * **/
			if(isAdd) {
				pool.setCacheMemoryUsed(curentCacheSize + cacheSize);
			} else {
				pool.setCacheMemoryUsed(pool.getCacheMemory() - curentCacheSize);
			}
			
			mapper.updateByExampleSelective(pool, condition);
		}
	}

	/**
	 * 获得最空闲的Mcs资源
	 * @param num
	 * @return
	 */
	private List<McsResourcePool> getBestResource(int num) {
		McsResourcePoolMapper mapper = ServiceUtil.getMapper(McsResourcePoolMapper.class);
		McsResourcePoolCriteria condition = new McsResourcePoolCriteria();
		condition.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS);
		condition.setLimitStart(0);
		condition.setLimitEnd(num);
		condition.setOrderByClause("(ifnull(cache_memory, 0) - ifnull(cache_memory_used, 0)) desc");
		return mapper.selectByExample(condition);
	}

	/**
	 * 获得UseInstance中"失效"的记录
	 * @param host
	 * @return McsUserCacheInstance
	 */
	private McsUserCacheInstance getCanUseInstance(String host) throws PaasException {
		McsUserCacheInstanceCriteria condition = new McsUserCacheInstanceCriteria();
		condition.createCriteria().andStatusNotEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostEqualTo(host);
		condition.setLimitStart(0);
		condition.setLimitEnd(1);
		
		McsUserCacheInstance bean = null;
		McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
		List<McsUserCacheInstance> list = im.selectByExample(condition);
		if (list != null && list.size() > 0) {
			bean = list.get(0);
		}
		
		return bean;
	}

	/**
	 * 更新Mcs资源池
	 * @param mcsResourcePool
	 * @return
	 */
	private int updateResource(McsResourcePool mcsResourcePool, int portOffset) throws PaasException {
		McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
		McsResourcePoolCriteria condition = new McsResourcePoolCriteria();
		/** TODO:端口偏移量，重点验证。**/
		condition.createCriteria().andIdEqualTo(mcsResourcePool.getId())
			.andCachePortEqualTo(mcsResourcePool.getCachePort() - portOffset); 
		return rpm.updateByExampleSelective(mcsResourcePool, condition);
	}
	
	/**
	 * 更新Mcs资源池
	 * @param mcsResourcePool
	 * @return
	 */
	private int updateResource(McsResourcePool mcsResourcePool) throws PaasException {
		McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
		McsResourcePoolCriteria condition = new McsResourcePoolCriteria();
		condition.createCriteria().andIdEqualTo(mcsResourcePool.getId()); 
		return rpm.updateByExampleSelective(mcsResourcePool, condition);
	}

	/**
	 * 停止Mcs服务，在管理控制台的“停止”／“重启”的功能中调用。
	 * @param userInstanceList
	 * @throws PaasException
	 */
	private void stopMcsInsForCacheIns(List<McsUserCacheInstance> userInstanceList) throws PaasException {
		McsResourcePool pool = null;
		McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
		for(McsUserCacheInstance tempIns: userInstanceList) {
			if (pool == null) {
				McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
				rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS)
						.andCacheHostIpEqualTo(tempIns.getCacheHost());
				List<McsResourcePool> pools = rpm.selectByExample(rpmc);
				pool = pools.get(0);
			}
			
			String cacheHostIp = tempIns.getCacheHost();
			Integer cachePort = tempIns.getCachePort();
			Integer agentPort = Integer.parseInt(pool.getAgentCmd());
			logger.info("------- redis info cacheHostIp:["+cacheHostIp+"]--------");
			logger.info("------- redis info cachePort:["+cachePort+"]--------");
			logger.info("------- redis info agentPort:["+agentPort+"]--------");
		
			/** 获取agent客户端 **/
			AgentClient ac = new AgentClient(cacheHostIp, agentPort);
			
			stopMcsIns(ac, tempIns.getCachePort());
		}
	}

	/**
	 * 启动mcs服务
	 * @param userId
	 * @param serviceId
	 * @param userInstance
	 * @throws PaasException
	 */
	private void startMcs(String userId, String serviceId, List<McsUserCacheInstance> userInstance) throws PaasException {
		McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
		McsResourcePool pool = null;
		
		String clusterInfo = "";
		for (McsUserCacheInstance tempIns :userInstance) {
			if (pool == null) {
				McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
				rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(tempIns.getCacheHost());
				List<McsResourcePool> pools = rpm.selectByExample(rpmc);
				pool = pools.get(0);
			}

			String cacheHostIp = tempIns.getCacheHost();
			Integer cachePort = tempIns.getCachePort();
			
			String cachePath = pool.getCachePath();
			Integer agentPort = Integer.parseInt(pool.getAgentCmd());
			String commonconfigPath = cachePath + McsConstants.FILE_PATH;
			
			logger.info("------- redis info cacheHostIp:["+cacheHostIp+"]-----------");
			logger.info("------- redis info cachePort:["+cachePort+"]-----------");
			logger.info("------- redis info agentPort:["+agentPort+"]-----------");
			logger.info("------- redis info commonconfigPath:["+commonconfigPath+"]-------");
			
			/** 获取agent客户端 **/
			AgentClient ac = new AgentClient(cacheHostIp, agentPort);
			
			if (userInstance.size() == 1) {
				startMcsIns(ac, commonconfigPath, cachePort);
			} else {
				startMcsInsForCluster(ac, cachePath, userId + "_" + serviceId, pool.getCachePath(), tempIns.getCachePort());
			}
			
			/** 生成集群的 ip:port 串，用于拼装创建集群的命令。 **/
			clusterInfo += " " + tempIns.getCacheHost() + ":" + tempIns.getCachePort();
		}
		
		/** 如果Mcs服务为集群模式，需要执行创建redis集群的命令。 **/
		if (userInstance.size() > 1) {
			String cacheHost = userInstance.get(0).getCacheHost();
			Integer agentPort = Integer.valueOf(pool.getAgentCmd());
			String cachePath = pool.getCachePath() + McsConstants.CLUSTER_FILE_PATH;
			AgentClient ac = new AgentClient(cacheHost, agentPort);
			
			logger.info("------创建["+userId+"]-["+serviceId+"]的MCS集群:"+clusterInfo);
			createRedisCluster(ac, cachePath, clusterInfo);
		}
	}

	/**
	 * 停redis，删除mcs服务端的配置文件，修改用户实例为失效
	 * 
	 * @param userInstanceList
	 * @param cacheSize
	 * @throws PaasException
	 */
	private void removeMcsServerFileAndUserIns(String userId, String serviceId, 
			List<McsUserCacheInstance> userInstanceList) throws PaasException {
		if (userInstanceList.size() == 1) {
			logger.info("-------- removeMcsServerFileAndUserIns ---single-----");
			McsUserCacheInstance tempIns = userInstanceList.get(0);
			McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
			McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
			rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(tempIns.getCacheHost());
			List<McsResourcePool> pools = rpm.selectByExample(rpmc);
			McsResourcePool pool = pools.get(0);

			String cachePath = pool.getCachePath();
			Integer agentPort = Integer.parseInt(pool.getAgentCmd());
			
			String cacheHostIp = tempIns.getCacheHost();
			Integer cachePort = tempIns.getCachePort();
			String requirepass = tempIns.getPwd();
			String commonconfigPath = cachePath + McsConstants.FILE_PATH;
			logger.info("------- redis info cacheHostIp:["+cacheHostIp+"]-------------");
			logger.info("------- redis info cachePort:["+cachePort+"]-------------");
			logger.info("------- redis info requirepass:["+requirepass+"]-------------");
			logger.info("------- redis info agentPort:["+agentPort+"]-------------");
			logger.info("------- redis info commonconfigPath:["+commonconfigPath+"]--------");
			
			/** 获取agent客户端 **/
			AgentClient ac = new AgentClient(cacheHostIp, agentPort);
			
			/** 停redis  **/
			logger.info("----- stop redis ["+cacheHostIp+"]:["+cachePort+"]  -----");
			stopMcsIns(ac, cachePort);
			
			/** 删除redis的配置文件  **/
			logger.info("----- delete redis config file-----");
			removeMcsSingleConfig(ac, commonconfigPath, cachePort);
			
			/** 删除zk的配置**/
			logger.info("------ delete config for ["+userId+"].["+serviceId+"] ------");
			CCSComponentOperationParam op = new CCSComponentOperationParam();
			op.setUserId(userId);
			op.setPath("/MCS/" + serviceId);
			op.setPathType(PathType.READONLY);
			iCCSComponentManageSv.delete(op);
			logger.info("------ delete ["+userId+"].["+serviceId+"]'s config successful! ------");
			
			/** 更新用户实例表，状态为2. **/
			logger.info("------- update mcs_user_instance status is ["+McsConstants.INVALIDATE_STATUS+"]");
			tempIns.setStatus(McsConstants.INVALIDATE_STATUS);
			McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
			im.updateByPrimaryKey(tempIns);

		} else {
			logger.info("-------- removeMcsServerFileAndUserIns ---cluster-----");
			McsResourcePool pool = null;
			for (McsUserCacheInstance tempIns :userInstanceList) {
				if (pool == null) {
					McsResourcePoolMapper rpm = ServiceUtil.getMapper(McsResourcePoolMapper.class);
					McsResourcePoolCriteria rpmc = new McsResourcePoolCriteria();
					rpmc.createCriteria().andStatusEqualTo(McsConstants.VALIDATE_STATUS).andCacheHostIpEqualTo(tempIns.getCacheHost());
					List<McsResourcePool> pools = rpm.selectByExample(rpmc);
					pool = pools.get(0);
				}
				
				String cachePath = pool.getCachePath();
				Integer agentPort = Integer.parseInt(pool.getAgentCmd());
				
				String cacheHostIp = tempIns.getCacheHost();
				Integer cachePort = tempIns.getCachePort();
				String commonconfigPath = cachePath + McsConstants.CLUSTER_FILE_PATH;
				logger.info("------- redis info cacheHostIp:["+cacheHostIp+"] ----------");
				logger.info("------- redis info cachePort:["+cachePort+"] ----------");
				logger.info("------- redis info agentPort:["+agentPort+"] ----------");
				logger.info("------- redis info commonconfigPath:["+commonconfigPath+"]-----------");
				
				/** 获取agent客户端 **/
				AgentClient ac = new AgentClient(cacheHostIp, agentPort);
				
				/** 停redis  **/
				logger.info("----- stop redis ["+cacheHostIp+"]:["+cachePort+"]  -----");
				stopMcsIns(ac, cachePort);
				
				/** 删除redis集群的目录及配置文件: ./redis/cluster/user_id+service_id/ **/
				logger.info("----- delete redis[cluster] config file -----");
				removeMcsClusterConfig(ac, commonconfigPath, cachePort, userId, serviceId);
				
				/** 删除zk的配置 **/
				logger.info("----- delete zk config for ["+userId+"].["+serviceId+"] ---cluster-----");
				CCSComponentOperationParam op = new CCSComponentOperationParam();
				op.setUserId(userId);
				op.setPath("/MCS/" + serviceId);
				op.setPathType(PathType.READONLY);
				iCCSComponentManageSv.delete(op);
				logger.info("----- delete zk info ["+userId+"].["+serviceId+"]'s config successful! ------");

				/** 更新用户实例表，状态为2. **/
				tempIns.setStatus(McsConstants.INVALIDATE_STATUS);
				McsUserCacheInstanceMapper im = ServiceUtil.getMapper(McsUserCacheInstanceMapper.class);
				im.updateByPrimaryKey(tempIns);
				logger.info("----- update mcs_user_instance status is ["+McsConstants.INVALIDATE_STATUS+"]");
			}
		}
	}
	
	/**
	 * 使用新的agent接口，启动redis服务。
	 * @param ac
	 * @param path
	 * @param port
	 * @throws PaasException
	 */
	private void startMcsIns(AgentClient ac, String path, int port) throws PaasException {
		try {
			String cmd_path = path + port + "/";
			String cmd_start = "redis-server " + path + port + "/redis-" + port + ".conf &";
			ac.executeInstruction(cmd_path, cmd_start);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("启动MCS异常，port：" + port, e);
		}
	}

	/**
	 * 使用新的agent接口，启动redis服务。
	 * @param ac
	 * @param path
	 * @param port
	 * @throws PaasException
	 */
	private void startMcsInsForCluster(AgentClient ac, String cachePath, String dir, String cPath, int port) throws PaasException {
		try {
			String path = cachePath + McsConstants.CLUSTER_FILE_PATH + dir + "/" + port + "/";
			String cmd_rm = "rm -rf appendonly.aof dump.rdb nodes.conf ";
			String cmd = "redis-server " + cachePath + McsConstants.CLUSTER_FILE_PATH + dir + "/" + port
					+ "/redis-" + port + ".conf > redis.log &";
			ac.executeInstruction(path, cmd_rm);
			ac.executeInstruction(path, cmd);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("启动MCS异常，port：" + port, e);
		}
	}

	/**
	 * 使用新的agent接口，停redis服务。
	 * @param ac
	 * @param port
	 * @throws PaasException
	 */
	private void stopMcsIns(AgentClient ac, int port) throws PaasException {
		try {
			String cmd =  "ps -ef | grep " + port + " | awk '{print $2}' | xargs kill -9";
			ac.executeInstruction(cmd);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("++++++++ 停止Redis异常，port：" + port, e);
		}
	}
	
	/**
	 * 删除单节点的redis配置文件
	 * @param ac
	 * @param commonconfigPath
	 * @param cachePort
	 * @param userId
	 * @param serviceId
	 * @throws PaasException
	 */
	private void removeMcsSingleConfig(AgentClient ac, String commonconfigPath, Integer cachePort) throws PaasException {
		try {
			String path = commonconfigPath + cachePort + "/";
			String cmd = "rm -rf " + path;
			ac.executeInstruction(commonconfigPath, cmd);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("集群--删除redis的配置文件异常，fileInfo：" + commonconfigPath+"/"+cachePort, e);
		}
	}
	
	/**
	 * 删除集群的redis目录及配置文件
	 * @param ac
	 * @param commonconfigPath
	 * @param cachePort
	 * @param userId
	 * @param serviceId
	 * @throws PaasException
	 */
	private void removeMcsClusterConfig(AgentClient ac, String commonconfigPath, Integer cachePort, 
			String userId, String serviceId) throws PaasException {
		try {
			String path = commonconfigPath + userId + "_" + serviceId + "/";
			String cmd = "rm -rf " + path;
			ac.executeInstruction(path, cmd);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("集群--删除redis的配置文件异常，fileInfo：" + commonconfigPath+userId+"/"+cachePort, e);
		}
	}

	/**
	 * 执行Redis集群创建的命令。
	 * @param ac
	 * @param clusterInfo
	 * @throws PaasException
	 */
	private void createRedisCluster(AgentClient ac, String cmdPath, String clusterInfo) throws PaasException {
		try {
			String cmd_create_cluster = "redis-trib.rb create --replicas 1 " + clusterInfo;
			ac.executeInstruction(cmdPath, cmd_create_cluster);
			logger.info("----------创建Redis集群["+clusterInfo+"]成功----------");
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new PaasException("++++ 创建Redis集群["+clusterInfo+"]失败：" + e.getMessage(), e);
		}
	}
}