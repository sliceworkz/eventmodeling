/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventmodeling.events;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;

public class InstanceFactory {

    private static final String HOSTNAME = generateHostname();
    private static final String PROCESS_ID = generateProcessId();

    private static final String DEFAULT = "default";
    
    public static Instance determine ( String logicalName ) {
    	return determine(logicalName, null);
    }

    public static Instance determine ( String logical , String physical ) {
    	return new Instance(logical, physical==null?DEFAULT:physical, PROCESS_ID);
    }
	
    private static String generateHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Fallback if hostname resolution fails
            String fallbackHost = System.getProperty("user.name", "unknown");
            return "%s-%d-%d".formatted(fallbackHost, 
                ProcessHandle.current().pid(), System.currentTimeMillis());
        }
    }

    private static String generateProcessId() {
        // Host-specific component
        String hostname = getHostname();
        
        // Process-specific component  
        String processId = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        
        // Startup timestamp for uniqueness across restarts
        long startTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        
        // Optional: Add a short random component for extra collision resistance
        String random = Integer.toHexString(new Random().nextInt(0xFFFF));
        
        return "%s-%s-%d-%s".formatted(hostname, processId, startTime, random);
    }
    
    public static String getHostname() {
        return HOSTNAME;
    }

    public static String getInstanceId() {
        return PROCESS_ID;
    }
}
