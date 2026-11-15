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
package org.sliceworkz.eventmodeling.ports;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What one call through a port consumed of a paid service, in the units that service bills: the tokens of a
 * model, the segments of an SMS, the requests of a geocoding API. An adapter reports it to the call it runs in
 * ({@link PortCall#current()}), and the framework puts it on the call's port event and its observation, so usage
 * can be followed per bounded context, slice, port and caller like the call itself.
 * <p>
 * The framework knows no kind of service. A quantity is a name and an amount, named by the provider's own billing
 * units, never invented ones, so the numbers can be checked against the provider's invoice line by line. A
 * convention for a kind of service (the model token names, for instance) lives with whoever uses it.
 * <p>
 * A payload record: it travels inside the monitoring events, so its canonical constructor is lenient and only
 * {@link #of} checks. Its derived methods carry no {@code get}/{@code is} prefix, so a JSON mapper never writes
 * them as properties.
 *
 * @param provider       who provides the service and bills it ({@code anthropic}, {@code twilio})
 * @param product        what was asked for (a model, an SMS route, an API)
 * @param servedProduct  what was actually used and billed, when it differs from what was asked (a fallback model);
 *                       {@code null} when it is the product asked for
 * @param quantities     the billed units and their amounts, in the order they were reported
 * @param reference      the provider's id for the call, to reconcile with its own reports; may be {@code null}
 * @param attributes     a few extras of low cardinality (a region, a tier); never personal data or an id per call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Consumption ( String provider, String product, String servedProduct, Map<String, Long> quantities,
		String reference, Map<String, String> attributes ) {

	public Consumption {
		quantities = quantities == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
		attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
	}

	/**
	 * Consumption of one provider's product, with no quantity yet.
	 *
	 * @throws IllegalArgumentException for a blank provider or product
	 */
	public static Consumption of ( String provider, String product ) {
		return new Consumption(required(provider, "provider"), required(product, "product"), null, Map.of(), null, Map.of());
	}

	/** The same consumption, served by another product than the one asked for. */
	public Consumption served ( String servedProduct ) {
		String served = servedProduct == null || servedProduct.isBlank() || servedProduct.strip().equals(product) ? null : servedProduct.strip();
		return new Consumption(provider, product, served, quantities, reference, attributes);
	}

	/**
	 * The same consumption with an amount of one billed unit added; reporting a unit twice adds the amounts.
	 *
	 * @throws IllegalArgumentException for a blank name or a negative amount
	 */
	public Consumption quantity ( String name, long amount ) {
		String unit = required(name, "quantity name");
		if ( amount < 0 ) {
			throw new IllegalArgumentException("a consumed quantity cannot be negative: %s %d".formatted(unit, amount));
		}
		Map<String, Long> more = new LinkedHashMap<>(quantities);
		more.merge(unit, amount, Long::sum);
		return new Consumption(provider, product, servedProduct, more, reference, attributes);
	}

	/** The same consumption with the provider's id for the call. */
	public Consumption reference ( String reference ) {
		return new Consumption(provider, product, servedProduct, quantities, reference == null || reference.isBlank() ? null : reference.strip(), attributes);
	}

	/** The same consumption with one more attribute, of low cardinality. */
	public Consumption attribute ( String name, String value ) {
		Map<String, String> more = new LinkedHashMap<>(attributes);
		more.put(required(name, "attribute name"), value);
		return new Consumption(provider, product, servedProduct, quantities, reference, more);
	}

	/** The product that was billed: the one served, or the one asked for when they are the same. */
	public String billedProduct ( ) {
		return servedProduct != null ? servedProduct : product;
	}

	/** The amount of one billed unit, 0 when it was not reported. */
	public long amount ( String name ) {
		return quantities.getOrDefault(name, 0L);
	}

	/**
	 * This consumption and another of the same provider and billed product, summed: the quantities added up,
	 * the reference and the attributes, which belong to one call, left out.
	 */
	public Consumption plus ( Consumption other ) {
		if ( !provider.equals(other.provider) || !billedProduct().equals(other.billedProduct()) ) {
			throw new IllegalArgumentException("only the consumption of one provider's product adds up: %s/%s and %s/%s"
					.formatted(provider, billedProduct(), other.provider, other.billedProduct()));
		}
		Map<String, Long> sum = new LinkedHashMap<>(quantities);
		other.quantities.forEach(( name, amount ) -> sum.merge(name, amount, Long::sum));
		return new Consumption(provider, billedProduct(), null, sum, null, Map.of());
	}

	private static String required ( String value, String what ) {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("a consumption needs a " + what);
		}
		return value.strip();
	}
}
