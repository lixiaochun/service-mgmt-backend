package com.ai.paas.ipaas.rds.service.impl;

import java.util.List;

import com.ai.paas.ipaas.rds.dao.wo.ResourcePool;


/** 
 * @author  作者 “WTF” E-mail: 1031248990@qq.com
 * @date 创建时间：2016年7月13日 下午2:25:52 
 * @version 
 * @since  
 */
public interface ChoiceRes {

	public ResourcePool choiceOne(List<ResourcePool> canUseResList);
	public ResourcePool choiceOne(List<ResourcePool> canUseResList, List<ResourcePool> exceptList);
}
