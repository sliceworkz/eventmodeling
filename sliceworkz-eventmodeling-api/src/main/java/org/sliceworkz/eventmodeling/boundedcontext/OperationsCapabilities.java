/*
 * Sliceworkz Event Modeling - an opinionated Event Modeling framework in Java
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventmodeling.boundedcontext;

import org.sliceworkz.eventmodeling.automation.AutomationAdminCapability;

/**
 * What an operator may do with a bounded context: see the moving parts, and stop or restart them.
 * <p>
 * This is the surface an admin endpoint or a dashboard should hold — the automations and the
 * projectors of this instance, their status and their lifecycle, and nothing about the domain. It
 * carries no read, no command and no erasure, so an operator tool cannot decide anything on the
 * application's behalf.
 * <p>
 * Both halves address <em>the instance they are called on</em>, since every instance runs its own
 * processors. Reaching another instance is the management stream's job
 * ({@link org.sliceworkz.eventmodeling.management.ManagementInstruction}), which ends up calling
 * exactly these methods on the instances an instruction names.
 *
 * <h2>Holding it</h2>
 * A built context already is one, because {@link AllCapabilities} extends this interface:
 * <pre>{@code
 * @Bean
 * OperationsCapabilities bankingOps ( Banking banking ) {
 *     return banking;
 * }
 * }</pre>
 * <p>
 * {@link LifecycleCapability} is deliberately not here: stopping the whole context is the owner's,
 * and an operator stops a named automation or processor instead. Nor is {@link PrivacyCapability} —
 * an erasure is a request from a person, answered under an authority an operations tool does not
 * carry. This is a boundary of discipline, not of security; see {@code WHO-MAY-DO-WHAT.md}.
 */
public interface OperationsCapabilities extends
	AutomationAdminCapability,
	ProcessorAdminCapability {

}
