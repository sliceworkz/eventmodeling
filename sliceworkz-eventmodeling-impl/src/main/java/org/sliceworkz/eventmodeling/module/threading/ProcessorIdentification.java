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
package org.sliceworkz.eventmodeling.module.threading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventmodeling.events.Instance;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

public record ProcessorIdentification ( String context, String type, String id, Storage storage, String location ) {

	public static final String TYPE_READMODEL = "readmodel";
	public static final String TYPE_TRANSLATOR = "translator";
	public static final String TYPE_DISPATCHER = "dispatcher";
	public static final String TYPE_AUTOMATION = "automation";

	public enum Storage {
		EPHEMERAL("ephemeral"),
		LOCAL("local"),
		SHARED("shared");

		private String label;
		private Storage ( String label ) {
			this.label = label;
		}
		public String label ( ) {
			return label;
		}
		public static Storage of ( String value ) {
			switch ( value ) {
				case "ephemeral":
					return EPHEMERAL;
				case "local":
					return LOCAL;
				case "shared":
					return SHARED;
				default:
					return null;
			}
		}
	}

	private static final char SEPARATOR = '/';

	public ProcessorIdentification ( String context, String type, String id, Storage storage, String location ) {

		validateNonEmpty("context", context);
		validateNonEmpty("type", type);
		validateNonEmpty("id", id);
		if ( storage == null ) {
			throw new IllegalArgumentException("storage specifier is required");
		}
		if ( location == null ) {
			if ( storage != Storage.SHARED ) {
				throw new IllegalArgumentException("storage specifier is required, unless for shared storage");
			}

		} else {
			if ( storage == Storage.SHARED ) {
				throw new IllegalArgumentException("shared storage cannot have a location specifier");
			}
		}
		this.context = context;
		this.type = type;
		this.id = id;
		this.storage = storage;
		this.location = location;
	}

	private void validateNonEmpty ( String name, String s ) {
		if ( s == null || s.strip().length() == 0 ) {
			throw new IllegalArgumentException("%s is required".formatted(name));
		}
	}

	public String toString ( ) {
		StringBuilder result = new StringBuilder();
		result.append(context);
		result.append(SEPARATOR);
		result.append(type);
		result.append(SEPARATOR);
		result.append(id);
		result.append("[");
		result.append(storage.label());
		switch (storage ) {
		case EPHEMERAL:
		case LOCAL:
			result.append(':');
			result.append(location);
			break;
		case SHARED:
			break;
		}
		result.append("]");
		return result.toString();
	}

	public static ProcessorIdentification parse ( String value ) {
		if ( value == null || value.trim().isEmpty() ) {
			throw new IllegalArgumentException("value is required");
		}

		java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
			"([^/]+)/([^/]+)/([^\\[]+)\\[([^:\\]]+)(?::([^\\]]+))?\\]"
		);

		java.util.regex.Matcher matcher = pattern.matcher(value);
		if ( !matcher.matches() ) {
			throw new IllegalArgumentException("Invalid format: expected 'context/type/id[storage:location]' or 'context/type/id[storage]'");
		}

		String context = matcher.group(1);
		String type = matcher.group(2);
		String id = matcher.group(3);
		String storageLabel = matcher.group(4);
		String location = matcher.group(5);

		Storage storage = Storage.of(storageLabel);
		if ( storage == null ) {
			throw new IllegalArgumentException("Invalid storage type: " + storageLabel);
		}

		if ( location != null && storage == Storage.SHARED ) {
			throw new IllegalArgumentException("Invalid format: shared storage cannot have location");
		}
		if ( location == null && storage != Storage.SHARED ) {
			throw new IllegalArgumentException("Invalid format: non-shared storage must have location");
		}

		return new ProcessorIdentification(context, type, id, storage, location);
	}

	public static class ProcessorIdentificationBuilder {

		private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorIdentificationBuilder.class);

		private Instance instance;
		private String context;
		private String type;
		private String name;
		private Storage storage;

		public static ProcessorIdentificationBuilder newBuilder ( Instance instance ) {
			return new ProcessorIdentificationBuilder(instance);
		}

		public ProcessorIdentificationBuilder ( Instance instance ) {
			this.instance = instance;
		}

		public ProcessorIdentificationBuilder context ( String context ) {
			this.context = context;
			return this;
		}

		public ProcessorIdentificationBuilder type ( String type ) {
			this.type = type;
			return this;
		}

		public ProcessorIdentificationBuilder readmodel ( ) {
			this.type = TYPE_READMODEL;
			return this;
		}

		public ProcessorIdentificationBuilder translator ( ) {
			this.type = TYPE_TRANSLATOR;
			return this;
		}

		public ProcessorIdentificationBuilder dispatcher ( ) {
			this.type = TYPE_DISPATCHER;
			return this;
		}

		public ProcessorIdentificationBuilder automation ( ) {
			this.type = TYPE_AUTOMATION;
			return this;
		}

		public ProcessorIdentificationBuilder name ( String name ) {
			this.name = name;
			return this;
		}

		public ProcessorIdentificationBuilder name ( Class<?> clazz ) {
			this.name = clazz.getSimpleName();
			return this;
		}

		public ProcessorIdentificationBuilder name ( Object object ) {
			return name(object.getClass());
		}

		public ProcessorIdentificationBuilder local ( ) {
			this.storage = Storage.LOCAL;
			return this;
		}

		public ProcessorIdentificationBuilder shared ( ) {
			this.storage = Storage.SHARED;
			return this;
		}

		public ProcessorIdentificationBuilder ephemeral ( ) {
			this.storage = Storage.EPHEMERAL;
			return this;
		}

		public ProcessorIdentification build ( ) {
			String location = null;
			switch ( storage ) {
				case EPHEMERAL:
					// ephemeral storage is gone once the process is gone, but references the specific instance it runs on, irregardless of the processId
					location = instance.logical() + "#" + instance.physical();
					break;
				case LOCAL:
					// local storage is tied to a single instance, but can survive restarts and picked up by a new process in case on non-memory storage
					location = instance.logical() + "#" + instance.physical();
					break;
				case SHARED:
					location = null;
					break;
				default:
					LOGGER.error("unhandled switch case for storage type {} - cannot determine location key", storage);
					throw new RuntimeException("unhandled switch case for storage");
			}
			return new ProcessorIdentification(context, type, name, storage, location);
		}

	}

	public Tags toTags ( Instance instance ) {
		Tags tags = Tags.of(
				Tag.of("x-context", context()),
				Tag.of("x-type", type()),
				Tag.of("x-id", id()),
				Tag.of("x-storage", storage().label()),
				Tag.of("x-location", location()),
				Tag.of("x-instance-logical", instance.logical()),
				Tag.of("x-instance-physical", instance.physical()),
				Tag.of("x-instance-process", instance.process())
		);
		return tags;
	}

}
