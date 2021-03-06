/***************************************************************************
 * Copyright (c) 2012 VMware, Inc. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.job.JobParametersExtractor;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.util.Assert;

/**
 * @author Jarred Li
 * @version 0.9
 * @since 0.9
 * 
 */
public class SubJobStep extends AbstractStep {
   private static final Logger logger = Logger.getLogger(SubJobStep.class);
   private static String subJobParametersKey = "subJobParameters";

   private Job job;
   private JobLauncher jobLauncher;
   private JobParametersExtractor jobParametersExtractor =
         new SubJobParametersExtractor();
   private JobExecutionStatusHolder jobExecutionStatusHolder;
   private JobExecutionStatusHolder mainJobExecutionStatusHolder;

   @Override
   public void afterPropertiesSet() throws Exception {
      super.afterPropertiesSet();
      Assert.state(jobLauncher != null, "A JobLauncher must be provided");
      Assert.state(job != null, "A Job must be provided");
   }

   /**
    * The {@link Job} to delegate to in this step.
    * 
    * @param job
    *           a {@link Job}
    */
   public void setJob(Job job) {
      this.job = job;
   }

   /**
    * A {@link JobLauncher} is required to be able to run the enclosed
    * {@link Job}.
    * 
    * @param jobLauncher
    *           the {@link JobLauncher} to set
    */
   public void setJobLauncher(JobLauncher jobLauncher) {
      this.jobLauncher = jobLauncher;
   }

   /**
    * The {@link JobParametersExtractor} is used to extract
    * {@link JobParametersExtractor} from the {@link StepExecution} to run the
    * {@link Job}. By default an instance will be provided that simply copies
    * the {@link JobParameters} from the parent job.
    * 
    * @param jobParametersExtractor
    *           the {@link JobParametersExtractor} to set
    */
   public void setJobParametersExtractor(
         JobParametersExtractor jobParametersExtractor) {
      this.jobParametersExtractor = jobParametersExtractor;
   }

   /**
    * @return the jobExecutionStatusHolder
    */
   public JobExecutionStatusHolder getJobExecutionStatusHolder() {
      return jobExecutionStatusHolder;
   }

   /**
    * @param jobExecutionStatusHolder
    *           the jobExecutionStatusHolder to set
    */
   public void setJobExecutionStatusHolder(
         JobExecutionStatusHolder jobExecutionStatusHolder) {
      this.jobExecutionStatusHolder = jobExecutionStatusHolder;
   }

   /**
    * @return the mainJobExecutionStatusHolder
    */
   public JobExecutionStatusHolder getMainJobExecutionStatusHolder() {
      return mainJobExecutionStatusHolder;
   }

   /**
    * @param mainJobExecutionStatusHolder
    *           the mainJobExecutionStatusHolder to set
    */
   public void setMainJobExecutionStatusHolder(
         JobExecutionStatusHolder mainJobExecutionStatusHolder) {
      this.mainJobExecutionStatusHolder = mainJobExecutionStatusHolder;
   }

   /**
    * Execute the job provided by delegating to the {@link JobLauncher} to
    * prevent duplicate executions. The job parameters will be generated by the
    * {@link JobParametersExtractor} provided (if any), otherwise empty. On a
    * restart, the job parameters will be the same as the last (failed)
    * execution.
    * 
    * @see AbstractStep#doExecute(StepExecution)
    */
   @Override
   protected void doExecute(StepExecution stepExecution) throws Exception {

      ExecutionContext executionContext = stepExecution.getExecutionContext();

      JobParameters jobParameters;
      if (executionContext.containsKey(subJobParametersKey)) {
         jobParameters =
               (JobParameters) executionContext.get(subJobParametersKey);
      } else {
         jobParameters =
               jobParametersExtractor.getJobParameters(job, stepExecution);
         executionContext.put(subJobParametersKey, jobParameters);
      }

      JobExecution subJobExecution = jobLauncher.run(job, jobParameters);
      //Wait for job completion
      while (true) {
         if (subJobExecution.getStatus().isRunning()) {
            double subJobProgress =
                  jobExecutionStatusHolder.getCurrentProgress(subJobExecution
                        .getId());
            mainJobExecutionStatusHolder.setCurrentStepProgress(stepExecution
                  .getJobExecution().getId(), subJobProgress);
            Thread.sleep(3000);
         } else {
            break;
         }
      }

      String nodeName = null;
      if (subJobExecution.getJobInstance().getJobParameters().getParameters()
            .containsKey(JobConstants.SUB_JOB_NODE_NAME)) {
         nodeName =
               subJobExecution.getJobInstance().getJobParameters()
                     .getString(JobConstants.SUB_JOB_NODE_NAME);
      } else {
         String stepName = stepExecution.getStepName();
         nodeName = stepName.substring(stepName.lastIndexOf("-") + 1);
      }
      ExecutionContext mainJobExecutionContext =
            stepExecution.getJobExecution().getExecutionContext();
      updateExecutionStatus(subJobExecution, nodeName, mainJobExecutionContext);
   }

   /**
    * update sub job status into main job's execution context for reporting
    * 
    * @param subJobExecution
    *           sub job execution
    * @param nodeName
    *           node name of sub job
    * @param mainJobExecutionContext
    *           main job execution context
    */
   private void updateExecutionStatus(JobExecution subJobExecution,
         String nodeName, ExecutionContext mainJobExecutionContext) {
      String rollbackStr =
            (String) subJobExecution.getExecutionContext().get(
                  JobConstants.SUB_JOB_FAIL_FLAG);
      boolean rollback = false;
      if (rollbackStr != null) {
         rollback = Boolean.parseBoolean(rollbackStr);
      }
      if (subJobExecution.getStatus().isUnsuccessful() || rollback) {
         Object errorMessageO =
               subJobExecution.getExecutionContext().get(
                     JobConstants.CURRENT_ERROR_MESSAGE);
         String errorMessage = null;
         if (errorMessageO != null) {
            errorMessage = (String)errorMessageO;
         }
         Object failedObj =
               mainJobExecutionContext.get(JobConstants.SUB_JOB_NODES_FAIL);
         List<NodeOperationStatus> failedNodes = null;
         if (failedObj == null) {
            failedNodes = new ArrayList<NodeOperationStatus>();
         } else {
            failedNodes = (ArrayList<NodeOperationStatus>) failedObj;
         }
         NodeOperationStatus failedSubJob =
               new NodeOperationStatus(nodeName, false, errorMessage);
         failedNodes.add(failedSubJob);
         mainJobExecutionContext.put(JobConstants.SUB_JOB_NODES_FAIL,
               failedNodes);
      } else {
         Object succeededObj =
               mainJobExecutionContext.get(JobConstants.SUB_JOB_NODES_SUCCEED);
         List<NodeOperationStatus> succeededNodes = null;
         if (succeededObj == null) {
            succeededNodes = new ArrayList<NodeOperationStatus>();
         } else {
            succeededNodes = (ArrayList<NodeOperationStatus>) succeededObj;
         }
         NodeOperationStatus succeededSubJob = new NodeOperationStatus(nodeName);
         succeededNodes.add(succeededSubJob);
         mainJobExecutionContext.put(JobConstants.SUB_JOB_NODES_SUCCEED,
               succeededNodes);
      }
   }
}
