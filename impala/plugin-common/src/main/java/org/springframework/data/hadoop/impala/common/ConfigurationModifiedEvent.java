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
package org.springframework.data.hadoop.impala.common;

import org.apache.hadoop.conf.Configuration;
import org.springframework.context.ApplicationEvent;

/**
 * Notification event that a Configuration object (attached) has been modified.
 *  
 * @author Costin Leau
 */
public class ConfigurationModifiedEvent extends ApplicationEvent {

	private static final long serialVersionUID = -1816900082971150722L;

	public ConfigurationModifiedEvent(Configuration config) {
		super(config);
	}

	public Configuration getConfiguration() {
		return (Configuration) this.getSource();
	}
}
