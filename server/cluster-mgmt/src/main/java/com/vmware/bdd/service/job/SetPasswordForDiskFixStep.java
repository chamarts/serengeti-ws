/***************************************************************************
 * Copyright (c) 2012-2013 VMware, Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************/
package com.vmware.bdd.service.job;

import org.apache.log4j.Logger;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;

import com.vmware.bdd.apitypes.ClusterCreate;
import com.vmware.bdd.entity.NodeEntity;
import com.vmware.bdd.exception.TaskException;
import com.vmware.bdd.manager.ClusterConfigManager;
import com.vmware.bdd.service.ISetPasswordService;

public class SetPasswordForDiskFixStep extends TrackableTasklet {
   private ISetPasswordService setPasswordService;
   private ClusterConfigManager configMgr;
   private static final Logger logger = Logger.getLogger(SetPasswordForDiskFixStep.class);

   @Override
   public RepeatStatus executeStep(ChunkContext chunkContext, JobExecutionStatusHolder jobExecutionStatusHolder) {
      logger.info("set password for disk fix");

      String clusterName = getJobParameters(chunkContext).getString(JobConstants.CLUSTER_NAME_JOB_PARAM);
      ClusterCreate clusterSpec = configMgr.getClusterConfig(clusterName);

      String newPassword = clusterSpec.getPassword();
      // if user didn't set password, return directly
      if (newPassword == null) {
         logger.info("User didn't set password.");
         return RepeatStatus.FINISHED;
      }

      String targetNode = getJobParameters(chunkContext).getString(JobConstants.SUB_JOB_NODE_NAME);
      NodeEntity nodeEntity = clusterEntityMgr.findNodeByName(targetNode);
      String fixedNodeIP = nodeEntity.getPrimaryMgtIpV4();
      if (fixedNodeIP == null) {
         throw TaskException.EXECUTION_FAILED("No fixed node need to set password for.");
      }

      boolean success = false;
      try {
         success = setPasswordService.setPasswordForNode(clusterName, fixedNodeIP, newPassword);
         putIntoJobExecutionContext(chunkContext, JobConstants.CLUSTER_EXISTING_NODES_JOB_PARAM, success);
      } catch (Exception e) {
         throw TaskException.EXECUTION_FAILED("In disk fix, failed to set password for node " + targetNode);
      }
      if (!success) {
         throw TaskException.EXECUTION_FAILED("In disk fix, failed to set password for node " + targetNode);
      }
      return RepeatStatus.FINISHED;
   }

   public ClusterConfigManager getConfigMgr() {
      return configMgr;
   }

   public void setConfigMgr(ClusterConfigManager configMgr) {
      this.configMgr = configMgr;
   }

   public ISetPasswordService getSetPasswordService() {
      return setPasswordService;
   }

   @Autowired
   public void setSetPasswordService(ISetPasswordService setPasswordService) {
      this.setPasswordService = setPasswordService;
   }
}