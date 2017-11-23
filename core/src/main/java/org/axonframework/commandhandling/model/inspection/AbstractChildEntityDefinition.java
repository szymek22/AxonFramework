/*
 * Copyright (c) 2010-2017. Axon Framework
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
 */

package org.axonframework.commandhandling.model.inspection;

import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.model.AggregateMember;
import org.axonframework.commandhandling.model.ForwardingMode;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.property.Property;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.axonframework.common.ObjectUtils.getOrDefault;
import static org.axonframework.common.annotation.AnnotationUtils.findAnnotationAttributes;
import static org.axonframework.common.property.PropertyAccessStrategy.getProperty;

/**
 * Abstract implementation of the {@link org.axonframework.commandhandling.model.inspection.ChildEntityDefinition} to
 * provide reusable functionality for collections of ChildEntityDefinitions.
 */
public abstract class AbstractChildEntityDefinition implements ChildEntityDefinition {

    @Override
    public <T> Optional<ChildEntity<T>> createChildDefinition(Field field, EntityModel<T> declaringEntity) {
        Map<String, Object> attributes = findAnnotationAttributes(field, AggregateMember.class).orElse(null);
        if (attributes == null || fieldIsOfType(field)) {
            return Optional.empty();
        }
        EntityModel<Object> childEntityModel = extractChildEntityModel(declaringEntity, attributes, field);

        Boolean forwardEvents = (Boolean) attributes.get("forwardEvents");
        ForwardingMode eventForwardingMode = (ForwardingMode) attributes.get("eventForwardingMode");

        return Optional.of(new AnnotatedChildEntity<>(
                childEntityModel,
                (Boolean) attributes.get("forwardCommands"),
                eventForwardingMode(forwardEvents, eventForwardingMode),
                (String) attributes.get("eventRoutingKey"),
                (msg, parent) -> resolveCommandTarget(msg, parent, field, childEntityModel),
                (msg, parent) -> resolveEventTarget(parent, field)
        ));
    }

    /**
     * Check whether the given {@link java.lang.reflect.Field} is of a type the
     * {@link org.axonframework.commandhandling.model.inspection.ChildEntityDefinition} supports. The Collection
     * implementation for example check whether the field is of type {@link java.lang.Iterable}.
     *
     * @param field A {@link java.lang.reflect.Field} containing a Child Entity.
     * @return true if the type is as required by the implementation and false if it is not.
     */
    protected abstract boolean fieldIsOfType(Field field);

    /**
     * Extracts the Child Entity contained in the given {@code declaringEntity} as an
     * {@link org.axonframework.commandhandling.model.inspection.EntityModel}. The type of the Child Entity is defined
     * through a key in the provided {@code attributes} or based on given {@link java.lang.reflect.Field}.
     *
     * @param declaringEntity The {@link org.axonframework.commandhandling.model.inspection.EntityModel} declaring the
     *                        given {@code field}.
     * @param attributes      A {@link java.util.Map} containing the
     *                        {@link org.axonframework.commandhandling.model.AggregateMember} attributes.
     * @param field           The {@link java.lang.reflect.Field} containing the Child Entity.
     * @param <T>             The type {@code T} of the given {@code declaringEntity}
     *                        {@link org.axonframework.commandhandling.model.inspection.EntityModel}.
     * @return the Child Entity contained in the {@code declaringEntity}.
     */
    protected abstract <T> EntityModel<Object> extractChildEntityModel(EntityModel<T> declaringEntity,
                                                                       Map<String, Object> attributes,
                                                                       Field field);

    /**
     * Retrieves the routing keys of every command handler on the given {@code childEntityModel} to be able to correctly
     * route commands to Entities.
     *
     * @param field            a {@link java.lang.reflect.Field} denoting the Child Entity upon which the {@code
     *                         childEntityModel} is based.
     * @param childEntityModel a {@link org.axonframework.commandhandling.model.inspection.EntityModel} to retrieve the
     *                         routing key properties from.
     * @return a {@link java.util.Map} of key/value types {@link java.lang.String}/
     * {@link org.axonframework.common.property.Property} from Command Message name to routing key.
     */
    @SuppressWarnings("WeakerAccess")
    protected Map<String, Property<Object>> extractCommandHandlerRoutingKeys(Field field,
                                                                             EntityModel<Object> childEntityModel) {
        return childEntityModel.commandHandlers()
                               .values()
                               .stream()
                               .map(commandHandler -> commandHandler.unwrap(CommandMessageHandlingMember.class)
                                                                    .orElse(null))
                               .filter(Objects::nonNull)
                               .collect(Collectors.toMap(
                                       CommandMessageHandlingMember::commandName,
                                       commandHandler -> extractCommandHandlerRoutingKey(childEntityModel,
                                                                                         commandHandler,
                                                                                         field
                                       )
                               ));
    }

    @SuppressWarnings("unchecked")
    private Property<Object> extractCommandHandlerRoutingKey(EntityModel<Object> childEntityModel,
                                                             CommandMessageHandlingMember commandHandler,
                                                             Field field) {
        String routingKey = getOrDefault(commandHandler.routingKey(), childEntityModel.routingKey());

        Property<Object> property = getProperty(commandHandler.payloadType(), routingKey);

        if (property == null) {
            throw new AxonConfigurationException(format(
                    "Command of type [%s] doesn't have a property matching the routing key [%s] necessary to route through field [%s]",
                    commandHandler.payloadType(),
                    routingKey,
                    field.toGenericString())
            );
        }
        return property;
    }

    private ForwardingMode eventForwardingMode(Boolean forwardEvents, ForwardingMode eventForwardingMode) {
        return !forwardEvents ? ForwardingMode.NONE : eventForwardingMode;
    }

    /**
     * Resolve the target of an incoming {@link org.axonframework.commandhandling.CommandMessage} to the right Child
     * Entity. Returns the Child Entity the {@code msg} needs to be routed to.
     *
     * @param msg              The {@link org.axonframework.commandhandling.CommandMessage} which is being resolved to a
     *                         target entity.
     * @param parent           The {@code parent} Entity of type {@code T} of this Child Entity.
     * @param field            The {@link java.lang.reflect.Field} containing the Child Entity.
     * @param childEntityModel The {@link org.axonframework.commandhandling.model.inspection.EntityModel} for the Child
     *                         Entity.
     * @param <T>              The type {@code T} of the given {@code parent} Entity.
     * @return The Child Entity which is the target of the incoming {@link org.axonframework.commandhandling.CommandMessage}.
     */
    protected abstract <T> Object resolveCommandTarget(CommandMessage<?> msg,
                                                       T parent,
                                                       Field field,
                                                       EntityModel<Object> childEntityModel);

    /**
     * Resolve the targets of an incoming {@link org.axonframework.eventhandling.EventMessage} to the right Child
     * Entities. Returns an {@link java.lang.Iterable} of all the Child Entities the Event Message needs to be routed
     * to.
     *
     * @param parent The {@code parent} Entity of type {@code T} of this Child Entity.
     * @param field  The {@link java.lang.reflect.Field} containing the Child Entity.
     * @param <T>    The type {@code T} of the given {@code parent} Entity.
     * @return An {@link java.lang.Iterable} of Child Entities which are the targets of the incoming
     * {@link org.axonframework.eventhandling.EventMessage}.
     */
    protected abstract <T> Iterable<Object> resolveEventTarget(T parent, Field field);
}

