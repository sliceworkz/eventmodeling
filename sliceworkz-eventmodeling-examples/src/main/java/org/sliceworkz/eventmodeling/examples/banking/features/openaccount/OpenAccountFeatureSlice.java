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
package org.sliceworkz.eventmodeling.examples.banking.features.openaccount;

import org.sliceworkz.eventmodeling.boundedcontext.BoundedContextBuilder;
import org.sliceworkz.eventmodeling.examples.banking.BankingBoundedContext;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingDomainEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingInboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingDomain.BankingOutboundEvent;
import org.sliceworkz.eventmodeling.examples.banking.BankingFeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice;
import org.sliceworkz.eventmodeling.slices.FeatureSlice.Type;

@FeatureSlice(type = Type.STATE_CHANGE, context="banking", chapter="Account management", tags= {"online"})
public class OpenAccountFeatureSlice implements BankingFeatureSlice {

	@Override
	public void configure(
			BoundedContextBuilder<BankingDomainEvent, BankingInboundEvent, BankingOutboundEvent> builder) {
		
	}

	@Override
	public void configure(BankingBoundedContext boundedContext) {
		
	}

}
