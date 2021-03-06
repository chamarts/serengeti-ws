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
package com.vmware.bdd.service.resmgmt.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.vmware.vim.binding.vim.net.IpConfigInfo;
import com.vmware.vim.binding.vim.vm.GuestInfo;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmware.aurora.global.Configuration;
import com.vmware.aurora.vc.VcCache;
import com.vmware.aurora.vc.VcDatastore;
import com.vmware.aurora.vc.VcNetwork;
import com.vmware.aurora.vc.VcResourcePool;
import com.vmware.aurora.vc.VcVirtualMachine;
import com.vmware.aurora.vc.vcservice.VcContext;
import com.vmware.aurora.vc.vcservice.VcSession;
import com.vmware.bdd.aop.annotation.RetryTransaction;
import com.vmware.bdd.apitypes.Datastore.DatastoreType;
import com.vmware.bdd.dal.IServerInfoDAO;
import com.vmware.bdd.entity.ServerInfoEntity;
import com.vmware.bdd.exception.VcProviderException;
import com.vmware.bdd.service.resmgmt.IDatastoreService;
import com.vmware.bdd.service.resmgmt.INetworkService;
import com.vmware.bdd.service.resmgmt.IResourceInitializerService;
import com.vmware.bdd.service.resmgmt.IResourcePoolService;
import com.vmware.bdd.utils.ConfigInfo;
import com.vmware.bdd.utils.Constants;
import com.vmware.vim.binding.vim.VirtualMachine;
import com.vmware.vim.binding.vmodl.ManagedObjectReference;

/**
 * @author Jarred Li
 * @since 0.8
 * @version 0.8
 *
 */
@Service
public class ResourceInitializerService implements IResourceInitializerService {

   public static final String DEFAULT_NETWORK = "defaultNetwork";
   public static final String DEFAULT_DS_SHARED = "defaultDSShared";
   public static final String DEFAULT_DS_LOCAL = "defaultDSLocal";
   public static final String DEFAULT_RP = "defaultRP";

   private static final Logger logger = Logger
         .getLogger(ResourceInitializerService.class);

   private IServerInfoDAO serverInfoDao;
   private IResourcePoolService rpSvc;
   private IDatastoreService dsSvc;
   private INetworkService networkSvc;

   /**
    * @return the serverInfoDao
    */
   public IServerInfoDAO getServerInfoDao() {
      return serverInfoDao;
   }


   /**
    * @param serverInfoDao
    *           the serverInfoDao to set
    */
   @Autowired
   public void setServerInfoDao(IServerInfoDAO serverInfoDao) {
      this.serverInfoDao = serverInfoDao;
   }


   /**
    * @return the rpSvc
    */
   public IResourcePoolService getRpSvc() {
      return rpSvc;
   }


   /**
    * @param rpSvc
    *           the rpSvc to set
    */
   @Autowired
   public void setRpSvc(IResourcePoolService rpSvc) {
      this.rpSvc = rpSvc;
   }


   /**
    * @return the dsSvc
    */
   public IDatastoreService getDsSvc() {
      return dsSvc;
   }


   /**
    * @param dsSvc
    *           the dsSvc to set
    */
   @Autowired
   public void setDsSvc(IDatastoreService dsSvc) {
      this.dsSvc = dsSvc;
   }


   /**
    * @return the networkSvc
    */
   public INetworkService getNetworkSvc() {
      return networkSvc;
   }


   /**
    * @param networkSvc
    *           the networkSvc to set
    */
   @Autowired
   public void setNetworkSvc(INetworkService networkSvc) {
      this.networkSvc = networkSvc;
   }


   /* (non-Javadoc)
    * @see com.vmware.bdd.service.IResourceInitializerService#initResource()
    */
   @Override
   public void initResource() {
      final String serverMobId =
            Configuration.getString(Constants.SERENGETI_SERVER_VM_MOBID);
      logger.info("server mob id:" + serverMobId);
      final VcVirtualMachine serverVm = findVM(serverMobId);
      VcResourcePool vcRP = getVmRp(serverVm);
      String clusterName = vcRP.getVcCluster().getName();
      String vcRPName = vcRP.getName();
      logger.info("vc rp: " + vcRPName + ", cluster: " + clusterName);
      String networkName = getVMNetwork(serverVm);
      Map<DatastoreType, List<String>> dsNames = getVmDatastore(serverVm);
      if (rpSvc.isDeployedUnderCluster(clusterName, vcRPName)) {
         vcRPName = "";
      }
      addResourceIntoDB(clusterName, vcRPName, networkName, dsNames);
   }


   /**
    * @param clusterName
    * @param vcRPName
    * @param networkName
    * @param dsNames
    */
   @Override
   @Transactional
   @RetryTransaction(2)
   public void addResourceIntoDB(String clusterName, String vcRPName,
         String networkName, Map<DatastoreType, List<String>> dsNames) {
      rpSvc.addResourcePool(DEFAULT_RP, clusterName, vcRPName);
      logger.info("added resource pool with vc rp:" + vcRPName);

      if (!dsNames.get(DatastoreType.SHARED).isEmpty()) {
         dsSvc.addDatastores(DEFAULT_DS_SHARED, DatastoreType.SHARED,
               dsNames.get(DatastoreType.SHARED), false);
      } else if (!dsNames.get(DatastoreType.LOCAL).isEmpty()) {
         dsSvc.addDatastores(DEFAULT_DS_LOCAL, DatastoreType.LOCAL,
               dsNames.get(DatastoreType.LOCAL), false);
      }
      logger.info("added datastore. " + dsNames);

      if (networkName != null) {
         networkSvc.addDhcpNetwork(DEFAULT_NETWORK, networkName);
         logger.info("added network:" + networkName);
      }
   }


   /**
    * @param serverVm
    * @return
    */
   private Map<DatastoreType, List<String>> getVmDatastore(
         final VcVirtualMachine serverVm) {
      Map<DatastoreType, List<String>> dsNames =
            new HashMap<DatastoreType, List<String>>();
      dsNames.put(DatastoreType.LOCAL, new ArrayList<String>());
      dsNames.put(DatastoreType.SHARED, new ArrayList<String>());
      for (VcDatastore ds : serverVm.getDatastores()) {
         if (ds.isLocal()) {
            dsNames.get(DatastoreType.LOCAL).add(ds.getName());
         } else {
            dsNames.get(DatastoreType.SHARED).add(ds.getName());
         }
         break;
      }
      return dsNames;
   }


   /**
    * @param serverVm
    * @return
    */
   private String getVMNetwork(final VcVirtualMachine serverVm) {
      String networkName = VcContext.inVcSessionDo(new VcSession<String>() {
         @Override
         protected String body() throws Exception {
            GuestInfo.NicInfo[] nicInfos = serverVm.queryGuest().getNet();

            if (nicInfos == null || nicInfos.length == 0) {
               return null;
            }

            String defaultNetwork = null;
            for (GuestInfo.NicInfo nicInfo : nicInfos) {
               if (nicInfo.getNetwork() == null) {
                  continue;
               }

               if (defaultNetwork == null) {
                  defaultNetwork = nicInfo.getNetwork();
               }

               if (nicInfo.getIpConfig() == null || nicInfo.getIpConfig().getIpAddress() == null
                     || nicInfo.getIpConfig().getIpAddress().length == 0) {
                  continue;
               }

               for (IpConfigInfo.IpAddress info : nicInfo.getIpConfig().getIpAddress()) {
                  if (info.getIpAddress() != null
                        && sun.net.util.IPAddressUtil.isIPv4LiteralAddress(info.getIpAddress())) {
                     return nicInfo.getNetwork();
                  }
               }
            }

            return defaultNetwork;
         }
      });
      logger.info("network name:" + networkName);
      return networkName;
   }

   @Override
   @Transactional(readOnly = true)
   public boolean isResourceInitialized() {
      boolean result = false;
      List<ServerInfoEntity> entities = serverInfoDao.findAll();
      if (entities != null && entities.size() == 1) {
         ServerInfoEntity entity = entities.get(0);
         if (entity.isResourceInitialized()) {
            result = true;
         }
      }
      logger.info("resource initialized? " + result);
      return result;
   }

   private VcResourcePool getVmRp(final VcVirtualMachine serverVm) {
      VcResourcePool vcRP =
            VcContext.inVcSessionDo(new VcSession<VcResourcePool>() {
               @Override
               protected VcResourcePool body() throws Exception {
                  if (ConfigInfo.isDeployAsVApp()) {
                     VcResourcePool vApp = serverVm.getParentVApp();
                     logger.info("vApp name: " + vApp.getName());
                     VcResourcePool vcRP = vApp.getParent();
                     return vcRP;
                  } else {
                     return serverVm.getResourcePool();
                  }
               }
            });
      return vcRP;
   }

   /**
    * @param serverMobId
    * @return
    */
   private VcVirtualMachine findVM(final String serverMobId) {
      final VcVirtualMachine serverVm =
            VcContext.inVcSessionDo(new VcSession<VcVirtualMachine>() {
               @Override
               protected VcVirtualMachine body() throws Exception {
                  VcVirtualMachine vm = VcCache.get(serverMobId);
                  if (vm == null) {
                     VcProviderException.SERVER_NOT_FOUND(serverMobId);
                  }
                  return vm;
               }
            });
      return serverVm;
   }

   @Override
   @Transactional
   @RetryTransaction(2)
   public void updateOrInsertServerInfo() {
      logger.info("start update/insert server info");
      List<ServerInfoEntity> entities = serverInfoDao.findAll();
      if (entities != null && entities.size() == 1) {
         ServerInfoEntity entity = entities.get(0);
         if (!entity.isResourceInitialized()) {
            entity.setResourceInitialized(true);
            entity.setVersion(Configuration
                  .getNonEmptyString("serengeti.version"));
            serverInfoDao.update(entity);
            logger.info("updated server info.");
         }
      } else {
         ServerInfoEntity entity = new ServerInfoEntity();
         entity.setResourceInitialized(true);
         entity.setVersion(Configuration
               .getNonEmptyString("serengeti.version"));
         serverInfoDao.insert(entity);
         logger.info("inserted server info.");
      }
   }
}