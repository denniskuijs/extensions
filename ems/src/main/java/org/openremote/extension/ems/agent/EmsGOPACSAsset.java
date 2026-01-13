/*
 * Copyright 2025, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.extension.ems.agent;

import jakarta.persistence.Entity;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

import java.util.Optional;

import static org.openremote.model.Constants.UNITS_KILO;
import static org.openremote.model.Constants.UNITS_WATT;

@Entity
public class EmsGOPACSAsset extends Asset<EmsGOPACSAsset> {
    public static final AttributeDescriptor<String> CONTRACTED_EAN = new AttributeDescriptor<>("contractedEAN", ValueType.TEXT
    );

    public static final AttributeDescriptor<Double> CURRENT_POWER_FLEX_REQUEST = new AttributeDescriptor<>("currentPowerFlexRequest", ValueType.NUMBER,
            new MetaItem<>(MetaItemType.DATA_POINTS_MAX_AGE_DAYS, 7),
            new MetaItem<>(MetaItemType.HAS_PREDICTED_DATA_POINTS),
            new MetaItem<>(MetaItemType.READ_ONLY),
            new MetaItem<>(MetaItemType.STORE_DATA_POINTS)
    ).withUnits(UNITS_KILO, UNITS_WATT);

    public static final AttributeDescriptor<Double> POWER_LIMIT_MAXIMUM_PROFILE_FLEX_ORDER = new AttributeDescriptor<>("powerLimitMaximumProfileFlexOrder", ValueType.NUMBER,
            new MetaItem<>(MetaItemType.DATA_POINTS_MAX_AGE_DAYS, 7),
            new MetaItem<>(MetaItemType.HAS_PREDICTED_DATA_POINTS),
            new MetaItem<>(MetaItemType.READ_ONLY),
            new MetaItem<>(MetaItemType.STORE_DATA_POINTS)
    ).withUnits(UNITS_KILO, UNITS_WATT);

    public static final AttributeDescriptor<Double> POWER_LIMIT_MINIMUM_PROFILE_FLEX_ORDER = new AttributeDescriptor<>("powerLimitMinimumProfileFlexOrder", ValueType.NUMBER,
            new MetaItem<>(MetaItemType.DATA_POINTS_MAX_AGE_DAYS, 7),
            new MetaItem<>(MetaItemType.HAS_PREDICTED_DATA_POINTS),
            new MetaItem<>(MetaItemType.READ_ONLY),
            new MetaItem<>(MetaItemType.STORE_DATA_POINTS)
    ).withUnits(UNITS_KILO, UNITS_WATT);

    public static final AttributeDescriptor<Double> POWER_MAXIMUM_FLEX_REQUEST = new AttributeDescriptor<>("powerMaximumFlexRequest", ValueType.NUMBER,
            new MetaItem<>(MetaItemType.DATA_POINTS_MAX_AGE_DAYS, 7),
            new MetaItem<>(MetaItemType.HAS_PREDICTED_DATA_POINTS),
            new MetaItem<>(MetaItemType.READ_ONLY),
            new MetaItem<>(MetaItemType.STORE_DATA_POINTS)
    ).withUnits(UNITS_KILO, UNITS_WATT);

    public static final AttributeDescriptor<Double> POWER_MINIMUM_FLEX_REQUEST = new AttributeDescriptor<>("powerMinimumFlexRequest", ValueType.NUMBER,
            new MetaItem<>(MetaItemType.DATA_POINTS_MAX_AGE_DAYS, 7),
            new MetaItem<>(MetaItemType.HAS_PREDICTED_DATA_POINTS),
            new MetaItem<>(MetaItemType.READ_ONLY),
            new MetaItem<>(MetaItemType.STORE_DATA_POINTS)
    ).withUnits(UNITS_KILO, UNITS_WATT);


    public static final AssetDescriptor<EmsGOPACSAsset> DESCRIPTOR = new AssetDescriptor<>("transmission-tower", null, EmsGOPACSAsset.class);

    protected EmsGOPACSAsset() {
    }

    public EmsGOPACSAsset(String name) {
        super(name);
    }

    public Optional<String> getContractedEan() {
        return getAttributes().getValue(CONTRACTED_EAN);
    }
}
