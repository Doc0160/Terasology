package org.terasology.entitySystem.persistence;

import com.google.common.base.Objects;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.primitives.SignedBytes;
import com.google.common.primitives.UnsignedBytes;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.PersistableEntityManager;
import org.terasology.entitySystem.Prefab;
import org.terasology.entitySystem.metadata.ClassMetadata;
import org.terasology.entitySystem.metadata.ComponentLibrary;
import org.terasology.entitySystem.metadata.ComponentMetadata;
import org.terasology.entitySystem.metadata.FieldMetadata;
import org.terasology.network.serialization.ServerComponentFieldCheck;
import org.terasology.protobuf.EntityData;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * @author Immortius
 */
public class PackedEntitySerializer {
    private static final Logger logger = LoggerFactory.getLogger(PackedEntitySerializer.class);

    private ComponentSerializeCheck componentSerializeCheck;
    private PersistableEntityManager entityManager;
    private ComponentLibrary componentLibrary;
    private BiMap<Class<? extends Component>, Integer> idTable = ImmutableBiMap.<Class<? extends Component>, Integer>builder().build();

    public PackedEntitySerializer(PersistableEntityManager entityManager, ComponentLibrary componentLibrary) {
        this.entityManager = entityManager;
        this.componentLibrary = componentLibrary;
    }

    public void setComponentSerializeCheck(ComponentSerializeCheck componentSerializeCheck) {
        this.componentSerializeCheck = componentSerializeCheck;
    }

    public ComponentSerializeCheck getComponentSerializeCheck() {
        return componentSerializeCheck;
    }

    public ComponentLibrary getComponentLibrary() {
        return componentLibrary;
    }

    public Map<Class<? extends Component>, Integer> getIdMapping() {
        return ImmutableMap.copyOf(idTable);
    }

    public void setIdMapping(Map<Class<? extends Component>, Integer> componentIdMapping) {
        this.idTable = ImmutableBiMap.copyOf(componentIdMapping);
    }

    public EntityData.PackedEntity serialize(EntityRef entity, boolean deltaAgainstPrefab, FieldSerializeCheck<Component> fieldCheck) {
        Prefab prefab = entity.getParentPrefab();
        if (prefab != null && deltaAgainstPrefab) {
            return serializeEntityDelta(entity, prefab, fieldCheck);
        } else {
            return serializeEntityFull(entity, fieldCheck);
        }
    }

    private EntityData.PackedEntity serializeEntityFull(EntityRef entityRef, FieldSerializeCheck<Component> fieldCheck) {
        EntityData.PackedEntity.Builder entity = EntityData.PackedEntity.newBuilder();

        ByteString.Output fieldIds = ByteString.newOutput();
        ByteString.Output componentFieldCounts = ByteString.newOutput();
        for (Component component : entityRef.iterateComponents()) {
            if (!componentSerializeCheck.serialize(componentLibrary.getMetadata(component.getClass()))) {
                continue;
            }

            serializeComponentFull(component, fieldCheck, entity, fieldIds, componentFieldCounts);
        }
        entity.setFieldIds(fieldIds.toByteString());
        entity.setComponentFieldCounts(componentFieldCounts.toByteString());

        return entity.build();
    }

    private EntityData.PackedEntity serializeEntityDelta(EntityRef entityRef, Prefab prefab, FieldSerializeCheck<Component> fieldCheck) {
        EntityData.PackedEntity.Builder entity = EntityData.PackedEntity.newBuilder();
        entity.setParentPrefabUri(prefab.getName());
        Set<Class<? extends Component>> presentClasses = Sets.newHashSet();

        ByteString.Output fieldIds = ByteString.newOutput();
        ByteString.Output componentFieldCounts = ByteString.newOutput();
        for (Component component : entityRef.iterateComponents()) {
            if (!componentSerializeCheck.serialize(componentLibrary.getMetadata(component.getClass()))) {
                continue;
            }

            presentClasses.add(component.getClass());

            Component prefabComponent = prefab.getComponent(component.getClass());

            if (prefabComponent == null) {
                serializeComponentFull(component, fieldCheck, entity, fieldIds, componentFieldCounts);
            } else {
                serializeComponentDelta(prefabComponent, component, fieldCheck, entity, fieldIds, componentFieldCounts);
            }
        }
        entity.setFieldIds(fieldIds.toByteString());
        entity.setComponentFieldCounts(componentFieldCounts.toByteString());

        for (Component prefabComponent : prefab.iterateComponents()) {
            if (!presentClasses.contains(prefabComponent.getClass()) && componentSerializeCheck.serialize(componentLibrary.getMetadata(prefabComponent.getClass()))) {
                entity.addRemovedComponent(idTable.get(prefabComponent.getClass()));
            }
        }
        return entity.build();
    }

    private void serializeComponentDelta(Component oldComponent, Component newComponent, FieldSerializeCheck<Component> fieldCheck, EntityData.PackedEntity.Builder entityData, ByteString.Output entityFieldIds, ByteString.Output componentFieldCounts) {
        ClassMetadata<?> componentMetadata = componentLibrary.getMetadata(oldComponent.getClass());
        if (componentMetadata == null) {
            logger.error("Unregistered component type: {}", oldComponent.getClass());
            return;
        }

        try {
            byte fieldCount = 0;
            for (FieldMetadata field : componentMetadata.iterateFields()) {
                if (fieldCheck.shouldSerializeField(field, newComponent)) {
                    Object oldValue = field.getValue(oldComponent);
                    Object newValue = field.getValue(newComponent);
                    if (!Objects.equal(oldValue, newValue)) {
                        EntityData.Value fieldValue = field.serializeValue(newValue);
                        if (fieldValue != null) {
                            entityFieldIds.write(field.getId());
                            entityData.addFieldValue(fieldValue);
                            fieldCount++;
                        } else {
                            logger.error("Exception serializing component type: {}, field: {} - returned null", componentMetadata, field);
                        }
                    }
                }
            }

            if (fieldCount > 0) {
                entityData.addComponentId(idTable.get(newComponent.getClass()));
                componentFieldCounts.write(fieldCount);
            }
        } catch (IOException e) {
            logger.error("Failed to serialize component {}", componentMetadata, e);
        }
    }

    private void serializeComponentFull(Component component, FieldSerializeCheck<Component> fieldCheck, EntityData.PackedEntity.Builder entityData, ByteString.Output entityFieldIds, ByteString.Output componentFieldCounts) {
        ClassMetadata<?> componentMetadata = componentLibrary.getMetadata(component.getClass());
        if (componentMetadata == null) {
            logger.error("Unregistered component type: {}", component.getClass());
        }

        try {
            entityData.addComponentId(idTable.get(component.getClass()));
            byte fieldCount = 0;
            for (FieldMetadata field : componentMetadata.iterateFields()) {
                if (fieldCheck.shouldSerializeField(field, component)) {
                    EntityData.Value fieldValue = field.serialize(component);
                    if (fieldValue != null) {

                        entityFieldIds.write(field.getId());

                        entityData.addFieldValue(fieldValue);
                        fieldCount++;
                    }
                }
            }

            componentFieldCounts.write(fieldCount);
        } catch (IOException e) {
            logger.error("Failed to serialize component {}", componentMetadata, e);
        }
    }

    public void deserializeOnto(EntityRef entity, EntityData.PackedEntity entityData) {
        deserializeOnto(entity, entityData, FieldSerializeCheck.NullCheck.<Component>newInstance());
    }

    public void deserializeOnto(EntityRef entity, EntityData.PackedEntity entityData, FieldSerializeCheck<Component> fieldCheck) {
        int fieldPos = 0;
        for (int componentIndex = 0; componentIndex < entityData.getComponentIdCount(); ++componentIndex) {
            Class<? extends Component> componentClass = idTable.inverse().get((Integer) entityData.getComponentId(componentIndex));
            ComponentMetadata<?> metadata = componentLibrary.getMetadata(componentClass);
            if (metadata == null) {
                logger.warn("Skipping unknown component {}", entityData.getComponentId(componentIndex));
                fieldPos += UnsignedBytes.toInt(entityData.getComponentFieldCounts().byteAt(componentIndex));
                continue;
            }
            if (!componentSerializeCheck.serialize(metadata)) {
                fieldPos += UnsignedBytes.toInt(entityData.getComponentFieldCounts().byteAt(componentIndex));
                continue;
            }

            Component existingComponent = entity.getComponent(metadata.getType());
            if (existingComponent == null) {
                existingComponent = metadata.newInstance();
            }
            for (int fieldIndex = 0; fieldIndex < UnsignedBytes.toInt(entityData.getComponentFieldCounts().byteAt(componentIndex)); ++fieldIndex) {
                byte fieldId = entityData.getFieldIds().byteAt(fieldPos);
                FieldMetadata fieldMetadata = metadata.getFieldById(fieldId);
                if (fieldMetadata != null && fieldCheck.shouldDeserializeField(fieldMetadata)) {
                    fieldMetadata.deserializeOnto(existingComponent, entityData.getFieldValue(fieldPos));
                }
                fieldPos++;
            }
            entity.addComponent(existingComponent);

        }

        for (int componentId : entityData.getRemovedComponentList()) {
            Class<? extends Component> componentClass = idTable.inverse().get((Integer) entityData.getComponentId(componentId));
            ComponentMetadata<?> metadata = componentLibrary.getMetadata(componentClass);
            if (componentSerializeCheck.serialize(metadata)) {
                entity.removeComponent(metadata.getType());
            }
        }
    }

    public EntityRef deserialize(EntityData.PackedEntity entityData) {
        EntityRef target;
        if (entityData.hasParentPrefabUri()) {
            target = entityManager.create(entityData.getParentPrefabUri());
        } else {
            target = entityManager.create();
        }
        deserializeOnto(target, entityData);
        return target;
    }


}
