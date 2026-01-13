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
package org.openremote.extension.ems.manager.optimisationMethods;

import org.openremote.extension.ems.agent.EmsDayAheadAsset;
import org.openremote.extension.ems.agent.EmsElectricityBatteryAsset;
import org.openremote.extension.ems.agent.EmsEnergyOptimisationAsset;
import org.openremote.extension.ems.agent.EmsGOPACSAsset;
import org.openremote.extension.ems.manager.Services;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.datapoint.ValueDatapoint;
import org.openremote.model.datapoint.query.AssetDatapointAllQuery;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.syslog.SyslogCategory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openremote.model.syslog.SyslogCategory.DATA;

public class EmsOptimisation implements OptimisationMethod {
    protected static final Logger LOG = SyslogCategory.getLogger(DATA, EmsOptimisation.class.getName());
    private final String optimisationMethodName = EmsOptimisation.class.getSimpleName();

    // Maximum interval between data-points send by the device to be considered connected
    private final int ACTIVE_PERIOD_MINUTES = 5;

    // EMS power limits
    private final int POWER_LIMIT_MAXIMUM_SAFETY_MARGIN_PERCENTAGE = 5;
    private final int POWER_LIMIT_MINIMUM_SAFETY_MARGIN_PERCENTAGE = 5;
    private final int POWER_LIMIT_MAXIMUM_BATTERIES_MARGIN_PERCENTAGE = 10;
    private final int POWER_LIMIT_MINIMUM_BATTERIES_MARGIN_PERCENTAGE = 10;

    // Default battery energy level percentage settings
    private final int BATTERY_ENERGY_LEVEL_PERCENTAGE_TARGET_DEFAULT = 50;
    private final int BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL = 5;


    @Override
    public void execute(String energyOptimisationAssetId, Services services) {
        runOptimisationMethodEms(energyOptimisationAssetId, services);
    }

    private void runOptimisationMethodEms(String energyOptimisationAssetId, Services services) {
        // Get energy optimisation asset
        EmsEnergyOptimisationAsset energyOptimisationAsset = (EmsEnergyOptimisationAsset) services.getAssetStorageService().find(energyOptimisationAssetId);

        if (energyOptimisationAsset == null) {
            return;
        }

        String logPrefixEnergyOptimisation = String.format("assetType='%s', assetId='%s', assetName='%s'", energyOptimisationAsset.getAssetType(), energyOptimisationAssetId, energyOptimisationAsset.getAssetName());

        // Get all battery assets
        List<EmsElectricityBatteryAsset> electricityBatteryAssets = services.getAssetStorageService()
                .findAll(new AssetQuery().parents(energyOptimisationAssetId).types(EmsElectricityBatteryAsset.class))
                .stream()
                .map(asset -> (EmsElectricityBatteryAsset) asset)
                .toList();

        if (energyOptimisationAsset.getEnableDetailedLogging().orElse(false)) {
            int allowChargingSize = electricityBatteryAssets
                    .stream()
                    .filter(electricityBatteryAsset -> electricityBatteryAsset.getAllowCharging().orElse(false))
                    .toList()
                    .size();

            int allowDischargingSize = electricityBatteryAssets
                    .stream()
                    .filter(electricityBatteryAsset -> electricityBatteryAsset.getAllowDischarging().orElse(false))
                    .toList()
                    .size();

            LOG.info(String.format("%s; Energy optimisation asset has %s battery asset(s). Number of batteries with '%s'= %s and '%s'= %s",
                    logPrefixEnergyOptimisation, electricityBatteryAssets.size(), EmsElectricityBatteryAsset.ALLOW_CHARGING.getName(), allowChargingSize, EmsElectricityBatteryAsset.ALLOW_DISCHARGING.getName(), allowDischargingSize));
        }

        // Check if power net is connected
        Double powerNet = energyOptimisationAsset.getPowerNet().orElse(null);

        if (powerNet == null) {
            LOG.warning(String.format("%s; Failed to perform '%s' energy optimisation method. '%s' attribute is not connected", logPrefixEnergyOptimisation, optimisationMethodName, EmsEnergyOptimisationAsset.POWER_NET.getName()));
        }

        // Validate power limits
        Double powerLimitMaximumProfileTotal = energyOptimisationAsset.getPowerLimitMaximumProfileTotal().orElse(null);
        Double powerLimitMinimumProfileTotal = energyOptimisationAsset.getPowerLimitMinimumProfileTotal().orElse(null);
        Long powerLimitMaximumProfileTimestampMillis = energyOptimisationAsset.getPowerLimitMaximumProfileTotalTimestamp().orElse(null);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

        String powerLimitMaximumProfileDateTime = "";

        if (powerLimitMaximumProfileTimestampMillis != null) {
            powerLimitMaximumProfileDateTime = formatter.format(Instant.ofEpochMilli(powerLimitMaximumProfileTimestampMillis));
        }

        if (powerLimitMaximumProfileTotal != null && powerLimitMinimumProfileTotal != null && powerLimitMaximumProfileTotal <= powerLimitMinimumProfileTotal) {
            LOG.warning(String.format("%s; Failed to perform '%s' energy optimisation method. The '%s'= %s kW is lower than or equal to '%s'= %s kW for timestamp='%s'",
                    logPrefixEnergyOptimisation, optimisationMethodName, EmsEnergyOptimisationAsset.POWER_LIMIT_MAXIMUM_PROFILE_TOTAL.getName(), powerLimitMaximumProfileTotal, EmsEnergyOptimisationAsset.POWER_LIMIT_MINIMUM_PROFILE_TOTAL.getName(), powerLimitMinimumProfileTotal, powerLimitMaximumProfileDateTime));
        }

        // Check for battery assets
        if (electricityBatteryAssets.isEmpty()) {
            return;
        }

        // Check connection status of battery assets
        batteryCheckConnection(electricityBatteryAssets, services);

        // Check if all required attributes are connected/set and create log messages
        batteryCheckSetup(electricityBatteryAssets);

        // Calculate new battery power set-points
        Map<String, Double> powerSetpointsNew = batteryCalculatePowerSetpoints(energyOptimisationAsset, electricityBatteryAssets, services, logPrefixEnergyOptimisation);

        // Update battery power set-points
        batteryUpdatePowerSetpoints(electricityBatteryAssets, powerSetpointsNew, services);
    }

    private Map<String, Double> batteryCalculatePowerSetpoints(EmsEnergyOptimisationAsset energyOptimisationAsset, List<EmsElectricityBatteryAsset> electricityBatteryAssets, Services services, String logPrefix) {
        Map<String, double[]> powerFlexibleAvailable = batteryCalculatePowerFlexibleAvailable(electricityBatteryAssets);
        Map<String, Double> powerSetpointsNew;

        // Get latest power set-point across all batteries
        long powerSetpointTimestampMillisLatest = 0L;

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            long powerSetpointTimestampMillis = electricityBatteryAsset.getPowerSetpointTimestamp().orElse(0L);

            if (powerSetpointTimestampMillis > powerSetpointTimestampMillisLatest) {
                powerSetpointTimestampMillisLatest = powerSetpointTimestampMillis;
            }
        }

        // Check if power net is updated since last battery power set-points update
        Double powerNet = energyOptimisationAsset.getPowerNet().orElse(null);
        long powerNetTimestampMillis = energyOptimisationAsset.getPowerNetTimestamp().orElse(0L);

        // Turn off batteries that do not have flexible power available during main power meter disconnect
        if (powerSetpointTimestampMillisLatest > powerNetTimestampMillis || powerNet == null) {
            powerSetpointsNew = batteryCheckPowerSetpointsCurrent(electricityBatteryAssets, powerFlexibleAvailable);
            return powerSetpointsNew;
        }

        // Check if power limits are present and calculate additional power limits
        Double powerLimitMaximumProfileTotal = energyOptimisationAsset.getPowerLimitMaximumProfileTotal().orElse(null);
        Double powerLimitMinimumProfileTotal = energyOptimisationAsset.getPowerLimitMinimumProfileTotal().orElse(null);

        Double powerLimitMaximumBatteries = null;

        if (powerLimitMaximumProfileTotal != null) {
            powerLimitMaximumBatteries = Math.round(powerLimitMaximumProfileTotal * (1 - POWER_LIMIT_MAXIMUM_BATTERIES_MARGIN_PERCENTAGE * 0.01) * 1000.0) / 1000.0;
        }

        Double powerLimitMinimumBatteries = null;

        if (powerLimitMinimumProfileTotal != null) {
            powerLimitMinimumBatteries = Math.round(powerLimitMinimumProfileTotal * (1 - POWER_LIMIT_MINIMUM_BATTERIES_MARGIN_PERCENTAGE * 0.01) * 1000.0) / 1000.0;
        }

        // TODO: add battery limits cross-over warning

        // Calculate battery energy level target
        int batteryEnergyLevelPercentageTarget = BATTERY_ENERGY_LEVEL_PERCENTAGE_TARGET_DEFAULT;

        if (powerLimitMaximumProfileTotal != null && powerLimitMinimumProfileTotal == null) {
            batteryEnergyLevelPercentageTarget = 100;
        } else if (powerLimitMaximumProfileTotal == null && powerLimitMinimumProfileTotal != null) {
            batteryEnergyLevelPercentageTarget = 0;
        }

        // Calculate battery energy level forecast
        Map<String, Integer> batteryEnergyLevelPercentageTargets = batteryCalculateEnergyLevelPercentageTargetsCurrent(batteryEnergyLevelPercentageTarget, electricityBatteryAssets, energyOptimisationAsset, services);

        // Calculate virtual power consumption
        Map<String, Double> powerSetpointsCurrent = new HashMap<>();

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();
            double powerSetpoint = electricityBatteryAsset.getPowerSetpoint().orElse(0.0);
            powerSetpointsCurrent.put(electricityBatteryAssetId, powerSetpoint);
        }

        double powerSetpointsCurrentSum = powerSetpointsCurrent.values().stream().mapToDouble(Double::doubleValue).sum();
        double powerConsumptionVirtual = powerNet - powerSetpointsCurrentSum;

        // TODO: change fixed percentage based power limit to a dynamic volatility limit

        // Calculate new power set-points
        if (powerLimitMaximumProfileTotal != null && powerConsumptionVirtual > powerLimitMaximumProfileTotal) {
            Double powerLimitMaximumVirtual = Math.round(powerLimitMaximumProfileTotal * (1 - POWER_LIMIT_MAXIMUM_SAFETY_MARGIN_PERCENTAGE * 0.01) * 1000.0) / 1000.0;
            powerSetpointsNew = batteryCalculatePowerSetpointsOnLimitBreach(powerFlexibleAvailable, powerConsumptionVirtual, powerLimitMaximumVirtual, "max");
        } else if (powerLimitMinimumProfileTotal != null && powerConsumptionVirtual < powerLimitMinimumProfileTotal) {
            Double powerLimitMinimumVirtual = Math.round(powerLimitMinimumProfileTotal * (1 - POWER_LIMIT_MINIMUM_SAFETY_MARGIN_PERCENTAGE * 0.01) * 1000.0) / 1000.0;
            powerSetpointsNew = batteryCalculatePowerSetpointsOnLimitBreach(powerFlexibleAvailable, powerConsumptionVirtual, powerLimitMinimumVirtual, "min");
        } else {
            powerSetpointsNew = batteryRestoreEnergyLevels(electricityBatteryAssets, powerFlexibleAvailable, powerNet, powerSetpointsCurrentSum, powerLimitMinimumBatteries, powerLimitMaximumBatteries, batteryEnergyLevelPercentageTargets);
        }

        // Create log messages on power limit breach
        double powerSetpointsNewSum = powerSetpointsNew.values().stream().mapToDouble(Double::doubleValue).sum();
        double powerNetNewVirtual = powerConsumptionVirtual + powerSetpointsNewSum;

        if (powerLimitMaximumProfileTotal != null && powerNet > powerLimitMaximumProfileTotal) {
            double powerExceededAmount = Math.round((powerNet - powerLimitMaximumProfileTotal) * 1000.0) / 1000.0;
            LOG.warning(String.format("%s; Power net is %s kW above power limit maximum", logPrefix, powerExceededAmount));

            if (powerNetNewVirtual > powerLimitMaximumProfileTotal) {
                double powerReductionShortage = Math.round((powerNetNewVirtual - powerLimitMaximumProfileTotal) * 1000.0) / 1000.0;
                LOG.warning(String.format("%s; Not enough flexible power to get below power limit maximum; Shortage of %s kW", logPrefix, powerReductionShortage));
            }
        } else if (powerLimitMinimumProfileTotal != null && powerNet < powerLimitMinimumProfileTotal) {
            double powerExceededAmount = Math.round((powerNet - powerLimitMinimumProfileTotal) * 1000.0) / 1000.0;
            LOG.warning(String.format("%s; Power net is %s kW below power limit minimum", logPrefix, powerExceededAmount));

            if (powerNetNewVirtual < powerLimitMinimumProfileTotal) {
                double powerReductionShortage = Math.round((powerNetNewVirtual - powerLimitMinimumProfileTotal) * 1000.0) / 1000.0;
                LOG.warning(String.format("%s; Not enough flexible power to get above power limit minimum; Shortage of %s kW", logPrefix, powerReductionShortage));
            }
        }

        return powerSetpointsNew;
    }

    private Map<String, Double> batteryRestoreEnergyLevels(List<EmsElectricityBatteryAsset> electricityBatteryAssets, Map<String, double[]> powerFlexibleAvailable, Double powerNet, Double powerSetpointsCurrentSum, Double powerLimitMinimumBatteries, Double powerLimitMaximumBatteries, Map<String, Integer> batteryEnergyLevelPercentageTargets) {
        Map<String, Double> powerSetpointsNew = new HashMap<>();

        // Find battery power set-points for a system without power limits
        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();
            Double energyLevelPercentage = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);

            // Set initial power set-point of 0
            powerSetpointsNew.put(electricityBatteryAssetId, 0.0);

            if (energyLevelPercentage == null) {
                continue;
            }

            double powerSetpointCurrent = electricityBatteryAsset.getPowerSetpoint().orElse(0.0);

            // Get battery energy level target
            Integer energyLevelPercentageTarget = batteryEnergyLevelPercentageTargets.get(electricityBatteryAssetId);

            if (energyLevelPercentageTarget == null) {
                energyLevelPercentageTarget = BATTERY_ENERGY_LEVEL_PERCENTAGE_TARGET_DEFAULT;
            }

            if (energyLevelPercentage < (energyLevelPercentageTarget - BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL)) {
                // Start charging
                double powerSetpointNew = powerFlexibleAvailable.get(electricityBatteryAssetId)[0];
                powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointNew);
            } else if (energyLevelPercentage > (energyLevelPercentageTarget + BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL)) {
                // Start discharging
                double powerSetpointNew = powerFlexibleAvailable.get(electricityBatteryAssetId)[1];
                powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointNew);
            } else if (powerSetpointCurrent > 0.0 && energyLevelPercentage < energyLevelPercentageTarget) {
                // Continue charging
                double powerSetpointNew = powerFlexibleAvailable.get(electricityBatteryAssetId)[0];
                powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointNew);
            } else if (powerSetpointCurrent < 0.0 && energyLevelPercentage > energyLevelPercentageTarget) {
                // Continue discharging
                double powerSetpointNew = powerFlexibleAvailable.get(electricityBatteryAssetId)[1];
                powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointNew);
            }
        }

        // Adjust battery power set-points in a system with power limits
        double powerSetpointsNewSum = powerSetpointsNew.values().stream().mapToDouble(Double::doubleValue).sum();
        double powerConsumptionVirtual = powerNet - powerSetpointsCurrentSum;

        Double chargingSpace = null;
        Double dischargingSpace = null;

        if (powerLimitMaximumBatteries != null) {
            chargingSpace = powerLimitMaximumBatteries - powerConsumptionVirtual;
        }

        if (powerLimitMinimumBatteries != null) {
            dischargingSpace = powerLimitMinimumBatteries - powerConsumptionVirtual;
        }

//        System.out.println("RESTORE");
//        System.out.println("chargingSpace: " + chargingSpace);
//        System.out.println("dischargingSpace: " + dischargingSpace);

        if (chargingSpace != null && powerSetpointsNewSum > chargingSpace) {
            // Adjust battery power set-points in case of charging space shortage
            double powerReduction = powerSetpointsNewSum - chargingSpace;

            for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
                String electricityBatteryAssetId = electricityBatteryAsset.getId();
                double powerSetpointNew = powerSetpointsNew.getOrDefault(electricityBatteryAssetId, 0.0);

                // Only adjust battery power set-point for charging batteries
                if (powerSetpointNew > 0.0) {
                    powerReduction = powerReduction - powerSetpointNew;

                    if (powerReduction >= 0.0) {
                        powerSetpointsNew.put(electricityBatteryAssetId, 0.0);
                    } else {
                        double powerSetpointAdjusted = Math.round(Math.min(-powerReduction, powerSetpointNew) * 1000.0) / 1000.0;
                        powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointAdjusted);
                        break;
                    }
                }
            }
        } else if (dischargingSpace != null && powerSetpointsNewSum < dischargingSpace) {
            // Adjust battery power set-points in case of discharging space shortage
            double powerReduction = powerSetpointsNewSum - dischargingSpace;

            for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
                String electricityBatteryAssetId = electricityBatteryAsset.getId();
                double powerSetpointNew = powerSetpointsNew.getOrDefault(electricityBatteryAssetId, 0.0);

                // Only adjust battery power set-point for discharging batteries
                if (powerSetpointNew < 0.0) {
                    powerReduction = powerReduction - powerSetpointNew;

                    if (powerReduction <= 0.0) {
                        powerSetpointsNew.put(electricityBatteryAssetId, 0.0);
                    } else {
                        double powerSetpointAdjusted = Math.max(-powerReduction, powerSetpointNew);
                        powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointAdjusted);
                        break;
                    }
                }
            }
        }

        return powerSetpointsNew;
    }

    private Map<String, Integer> batteryCalculateEnergyLevelPercentageTargetsCurrent(int batteryEnergyLevelPercentageTargetDefault, List<EmsElectricityBatteryAsset> electricityBatteryAssets, EmsEnergyOptimisationAsset energyOptimisationAsset, Services services) {
        // Calculate the general energy level percentage target forecast
        Map<Long, Integer> energyLevelPercentageTargetMap = calculateGeneralEnergyLevelPercentageTargetForecast(batteryEnergyLevelPercentageTargetDefault, energyOptimisationAsset, services);

        // Set default energy level percentage targets
        Map<String, Integer> batteryEnergyLevelPercentageTargetsCurrent = new HashMap<>();
        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String assetId = electricityBatteryAsset.getId();
            batteryEnergyLevelPercentageTargetsCurrent.put(assetId, BATTERY_ENERGY_LEVEL_PERCENTAGE_TARGET_DEFAULT);
        }

        if (energyLevelPercentageTargetMap.isEmpty()) {
            return batteryEnergyLevelPercentageTargetsCurrent;
        }

        // Calculate energy level percentage targets
        long currentTimeMillis = services.getTimerService().getCurrentTimeMillis();

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();

            Integer energyLevelPercentageMaximum = electricityBatteryAsset.getEnergyLevelPercentageMaximum().orElse(null);
            Integer energyLevelPercentageMinimum = electricityBatteryAsset.getEnergyLevelPercentageMinimum().orElse(null);

            if (energyLevelPercentageMaximum == null || energyLevelPercentageMinimum == null) {
                continue;
            }

            // Check if the energy level percentage maximum and minimum are valid
            if ((energyLevelPercentageMaximum - energyLevelPercentageMinimum) < BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL) {
                continue;
            }

            // Calculate the energy level percentage target forecast
            Map<Long, Integer> batteryEnergyLevelPercentageTargetMap = new HashMap<>();

            for (Map.Entry<Long, Integer> entry : energyLevelPercentageTargetMap.entrySet()) {
                long timestampMillis = entry.getKey();
                int value = entry.getValue();

                Integer batteryEnergyLevelPercentageTarget = null;

                if (value == BATTERY_ENERGY_LEVEL_PERCENTAGE_TARGET_DEFAULT) {
                    batteryEnergyLevelPercentageTarget = (energyLevelPercentageMaximum + energyLevelPercentageMinimum) / 2;
                } else if (value == 100 && energyLevelPercentageMaximum >= 100) {
                    batteryEnergyLevelPercentageTarget = 100;
                } else if (value == 100) {
                    batteryEnergyLevelPercentageTarget = energyLevelPercentageMaximum - BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL;
                } else if (value == 0) {
                    // TODO: Remove when power net forecast is included in the power set-point prediction
                    batteryEnergyLevelPercentageTarget = energyLevelPercentageMinimum + BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL + 25;
                    if (batteryEnergyLevelPercentageTarget > 100) {
                        batteryEnergyLevelPercentageTarget = 100;
                    }
//                    batteryEnergyLevelPercentageTarget = energyLevelPercentageMinimum + BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL;
                }

                batteryEnergyLevelPercentageTargetMap.put(timestampMillis, batteryEnergyLevelPercentageTarget);
            }

//            System.out.println("batteryEnergyLevelPercentageTargetMap");
//            batteryEnergyLevelPercentageTargetMap.entrySet().stream()
//                    .sorted(Map.Entry.<Long, Integer>comparingByKey().reversed())
//                    .forEach(entry -> System.out.println(entry.getKey() + "," + entry.getValue()));

            // Get the current energy level percentage target
            long energyLevelPercentageTargetTimestampCurrent = currentTimeMillis - currentTimeMillis % 900000;
            Integer energyLevelPercentageTargetCurrent = batteryEnergyLevelPercentageTargetMap.get(energyLevelPercentageTargetTimestampCurrent);
            batteryEnergyLevelPercentageTargetsCurrent.put(electricityBatteryAssetId, energyLevelPercentageTargetCurrent);

            // Update forecasts every 15 minutes
            long endTimeMillis = energyLevelPercentageTargetTimestampCurrent + 900000;
            AssetDatapointAllQuery assetDatapointQueryPredicted = new AssetDatapointAllQuery(currentTimeMillis, endTimeMillis);
            List<ValueDatapoint<?>> energyLevelPercentagePredictedCurrent = services.getAssetPredictedDatapointService().queryDatapoints(electricityBatteryAssetId, EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE.getName(), assetDatapointQueryPredicted);

//            System.out.println("energyLevelPercentagePredictedCurrent: " + energyLevelPercentagePredictedCurrent);

            int currentMinute = LocalDateTime.now().getMinute();

            if ((currentMinute % 15) == 0) {
                batteryUpdateForecasts(electricityBatteryAsset, currentTimeMillis, batteryEnergyLevelPercentageTargetMap, services);
            } else if (energyLevelPercentagePredictedCurrent.isEmpty()) {
                batteryUpdateForecasts(electricityBatteryAsset, currentTimeMillis, batteryEnergyLevelPercentageTargetMap, services);
            }
        }

//        System.out.println("batteryEnergyLevelPercentageTargets: " + batteryEnergyLevelPercentageTargetsCurrent);

        return batteryEnergyLevelPercentageTargetsCurrent;
    }

    private Map<Long, Integer> calculateGeneralEnergyLevelPercentageTargetForecast(int batteryEnergyLevelPercentageTargetDefault, EmsEnergyOptimisationAsset energyOptimisationAsset, Services services) {
        Map<Long, Integer> energyLevelPercentageTargetMap = new HashMap<>();

        // Get day ahead asset
        EmsDayAheadAsset dayAheadAsset = getDayAheadAsset(energyOptimisationAsset, services);
        boolean useDayAheadTariffs = false;

        if (dayAheadAsset != null) {
            String dayAheadAssetId = dayAheadAsset.getId();
            String logPrefixDayAhead = String.format("assetType='%s', assetId='%s', assetName='%s'", dayAheadAsset.getAssetType(), dayAheadAssetId, dayAheadAsset.getAssetName());

            useDayAheadTariffs = dayAheadAsset.getUseTariffDayAheadForecasts().orElse(false);
            String collectTimeForecasts = dayAheadAsset.getCollectTimeForecasts().orElse("");

            if (useDayAheadTariffs && collectTimeForecasts.isBlank()) {
                LOG.warning(String.format("%s, attributeName='%s'; Set time to collect day ahead forecasts", logPrefixDayAhead, EmsDayAheadAsset.COLLECT_TIME_FORECASTS.getName()));
            }

            // Parse the time string
            LocalTime collectTime = null;

            if (!collectTimeForecasts.isBlank()) {
                try {
                    collectTime = LocalTime.parse(collectTimeForecasts, DateTimeFormatter.ofPattern("HH:mm"));
                } catch (Exception e) {
                    LOG.warning(String.format("%s, attributeName='%s'; Error while parsing collect time; Exception: %s", logPrefixDayAhead, EmsDayAheadAsset.COLLECT_TIME_FORECASTS.getName(), e));
                }
            }

            // Collect the day ahead tariff forecasts at the desired collect time
            if (collectTime != null) {
                // Calculate the 15-minute interval for collecting the day ahead tariff forecasts
                LocalDate currentDate = LocalDate.now();
                LocalDateTime collectDateTime = LocalDateTime.of(currentDate, collectTime);
                long collectTimeMillisStart = collectDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long collectTimeMillisEnd = collectTimeMillisStart + 15 * 60000;
                long currentTimeMillis = services.getTimerService().getCurrentTimeMillis();

                if (currentTimeMillis >= collectTimeMillisStart && currentTimeMillis < collectTimeMillisEnd) {
                    // Create asset datapoint query
                    LocalDate tomorrowDate = currentDate.plusDays(1);
                    LocalDateTime startOfNextDay = tomorrowDate.atStartOfDay();
                    long startTimeMillis = startOfNextDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    long endTimeMillis = startTimeMillis + 24 * 60 * 60000 - 60000;
                    AssetDatapointAllQuery assetDatapointQuery = new AssetDatapointAllQuery(startTimeMillis, endTimeMillis);

                    // Get the tariffs number of data-points from day ahead asset
                    int tariffExportDayAheadSize = services.getAssetDatapointService().queryDatapoints(dayAheadAssetId, EmsDayAheadAsset.TARIFF_EXPORT_DAY_AHEAD.getName(), assetDatapointQuery).size();
                    int tariffImportDayAheadSize = services.getAssetDatapointService().queryDatapoints(dayAheadAssetId, EmsDayAheadAsset.TARIFF_IMPORT_DAY_AHEAD.getName(), assetDatapointQuery).size();

                    // Only update the historic datapoint table if there are no day ahead tariffs present
                    if (tariffExportDayAheadSize == 0) {
                        List<ValueDatapoint<?>> tariffExport = services.getAssetPredictedDatapointService().queryDatapoints(energyOptimisationAsset.getId(), EmsEnergyOptimisationAsset.TARIFF_EXPORT.getName(), assetDatapointQuery);
                        services.getAssetDatapointService().upsertValues(dayAheadAssetId, EmsDayAheadAsset.TARIFF_EXPORT_DAY_AHEAD.getName(), tariffExport);
                    }

                    if (tariffImportDayAheadSize == 0) {
                        List<ValueDatapoint<?>> tariffImport = services.getAssetPredictedDatapointService().queryDatapoints(energyOptimisationAsset.getId(), EmsEnergyOptimisationAsset.TARIFF_IMPORT.getName(), assetDatapointQuery);
                        services.getAssetDatapointService().upsertValues(dayAheadAssetId, EmsDayAheadAsset.TARIFF_IMPORT_DAY_AHEAD.getName(), tariffImport);
                    }

                    // Update the last update forecasts datetime field
                    String lastUpdateForecasts = dayAheadAsset.getLastUpdateForecasts().orElse("");
                    String lastUpdateForecastsNew = collectDateTime.toString();

                    LocalDateTime lastUpdateDateTime = collectDateTime.plusDays(-1);

                    if (!lastUpdateForecasts.isEmpty()) {
                        lastUpdateDateTime = LocalDateTime.parse(lastUpdateForecasts);
                    }

                    LocalDate nextUpdateDate = lastUpdateDateTime.plusDays(1).toLocalDate();

                    if (nextUpdateDate.isEqual(currentDate)) {
                        services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(dayAheadAssetId, EmsDayAheadAsset.LAST_UPDATE_FORECASTS.getName(), lastUpdateForecastsNew, collectTimeMillisStart));
                    }
                }
            }
        }

        // Get the tariff forecasts from energy optimisation asset for 1 week
        List<ValueDatapoint<?>> tariffExportDatapoints = getTariffDatapoints(energyOptimisationAsset, EmsEnergyOptimisationAsset.TARIFF_EXPORT.getName(), services);
        List<ValueDatapoint<?>> tariffImportDatapoints = getTariffDatapoints(energyOptimisationAsset, EmsEnergyOptimisationAsset.TARIFF_IMPORT.getName(), services);

        // Overwrite the current tariff forecasts with the day ahead tariff forecasts
        if (useDayAheadTariffs) {
            List<ValueDatapoint<?>> tariffExportDayAheadAssetDatapoints = getTariffDayAheadDatapoints(dayAheadAsset, EmsDayAheadAsset.TARIFF_EXPORT_DAY_AHEAD.getName(), services);
            List<ValueDatapoint<?>> tariffImportDayAheadAssetDatapoints = getTariffDayAheadDatapoints(dayAheadAsset, EmsDayAheadAsset.TARIFF_IMPORT_DAY_AHEAD.getName(), services);

            // Combine tariff export forecasts
            if (!tariffExportDayAheadAssetDatapoints.isEmpty()) {
                Map<Long, ValueDatapoint<?>> mergedMap = new HashMap<>();

                for (ValueDatapoint<?> dp : tariffExportDatapoints) {
                    mergedMap.put(dp.getTimestamp(), dp);
                }

                for (ValueDatapoint<?> dp : tariffExportDayAheadAssetDatapoints) {
                    mergedMap.put(dp.getTimestamp(), dp);
                }

                tariffExportDatapoints = new ArrayList<>(mergedMap.values());
                tariffExportDatapoints.sort(Comparator.comparingLong(ValueDatapoint::getTimestamp));
            }

            // Combine tariff import forecasts
            if (!tariffImportDayAheadAssetDatapoints.isEmpty()) {
                Map<Long, ValueDatapoint<?>> mergedMap = new HashMap<>();

                for (ValueDatapoint<?> dp : tariffImportDatapoints) {
                    mergedMap.put(dp.getTimestamp(), dp);
                }

                for (ValueDatapoint<?> dp : tariffImportDayAheadAssetDatapoints) {
                    mergedMap.put(dp.getTimestamp(), dp);
                }

                tariffImportDatapoints = new ArrayList<>(mergedMap.values());
                tariffImportDatapoints.sort(Comparator.comparingLong(ValueDatapoint::getTimestamp));
            }
        }

        // Return empty map when there is no tariff import forecast present
        if (tariffImportDatapoints.isEmpty()) {
            return energyLevelPercentageTargetMap;
        }

        // Create a map with 15-minute intervals and an initial default target energy level percentage
        long newestTimestampMillis = tariffImportDatapoints.stream()
                .mapToLong(ValueDatapoint::getTimestamp)
                .max().orElse(0L);

        long oldestTimestampMillis = tariffImportDatapoints.stream()
                .mapToLong(ValueDatapoint::getTimestamp)
                .min().orElse(0L);

        long startTimestampMillis = oldestTimestampMillis - oldestTimestampMillis % 900000;
        long endTimestampMillis = newestTimestampMillis - newestTimestampMillis % 900000;

        if ((endTimestampMillis - startTimestampMillis) <= 0) {
            return energyLevelPercentageTargetMap;
        }

        for (long timestampMillis = startTimestampMillis; timestampMillis <= endTimestampMillis; timestampMillis += 900000) {
            energyLevelPercentageTargetMap.put(timestampMillis, batteryEnergyLevelPercentageTargetDefault);
        }

        // TODO: with the change to 15-minute pricing, change from general to per battery to get the average best price for the battery charging duration
        // Find the best import price for each day
        ZoneId zoneId = ZoneId.systemDefault();

        Map<LocalDate, ValueDatapoint<Double>> tariffImportDailyMinimumMap = tariffImportDatapoints.stream()
                // Convert to concrete type
                .map(dp -> new ValueDatapoint<>(dp.getTimestamp(), (Double) dp.getValue()))
                // Group by LocalDate
                .collect(Collectors.toMap(dp -> Instant.ofEpochMilli(dp.getTimestamp())
                        .atZone(zoneId)
                        .toLocalDate(), Function.identity(), BinaryOperator.minBy(Comparator.comparing(ValueDatapoint::getValue))));

        long currentTimeMillis = services.getTimerService().getCurrentTimeMillis();
        LocalDate dateCurrent = Instant.ofEpochMilli(currentTimeMillis).atZone(zoneId).toLocalDate();
        ValueDatapoint<Double> tariffImportMinimumToday = tariffImportDailyMinimumMap.get(dateCurrent);

        if (tariffImportMinimumToday == null) {
            return energyLevelPercentageTargetMap;
        }

        for (int i = 0; i < tariffImportDailyMinimumMap.size(); i++) {
            ValueDatapoint<Double> tariffImportMinimumStart = tariffImportDailyMinimumMap.get(dateCurrent.plusDays(i - 1));
            ValueDatapoint<Double> tariffImportMinimumEnd = tariffImportDailyMinimumMap.get(dateCurrent.plusDays(i));

            // Set the values that cannot yet be predicted at the end of the forecast period to the last predicted value
            if (i == (tariffImportDailyMinimumMap.size() - 1) && tariffImportMinimumStart != null && tariffImportMinimumEnd == null) {
                long lastPredictedTimeMillis = tariffImportMinimumStart.getTimestamp() - 15 * 60000;
                Integer lastPredictedValue = energyLevelPercentageTargetMap.get(lastPredictedTimeMillis);

                for (Map.Entry<Long, Integer> entry : energyLevelPercentageTargetMap.entrySet()) {
                    long timeMillis = entry.getKey();

                    if (timeMillis > lastPredictedTimeMillis) {
                        entry.setValue(lastPredictedValue);
                    }
                }
            }

            if (tariffImportMinimumStart == null || tariffImportMinimumEnd == null) {
                continue;
            }

            long startTimeMillis = tariffImportMinimumStart.getTimestamp();
            long endTimeMillis = tariffImportMinimumEnd.getTimestamp();

            // Find the best export price for each day
            Optional<ValueDatapoint<Double>> tariffExportMinimum = tariffExportDatapoints.stream()
                    .filter(dp -> dp.getTimestamp() > startTimeMillis && dp.getTimestamp() < endTimeMillis)
                    .map(dp -> new ValueDatapoint<>(dp.getTimestamp(), (Double) dp.getValue()))
                    .min(Comparator.comparing(ValueDatapoint::getValue));

            // Check if there is a tariff export value-datapoint
            if (tariffExportMinimum.isEmpty()) {
                continue;
            }

            // Check if there is an import and export price
            if (tariffImportMinimumStart.getValue() == null || tariffExportMinimum.get().getValue() == null) {
                continue;
            }

            double tariffExportMinimumPrice = tariffExportMinimum.get().getValue();
            double tariffImportMinimumPrice = tariffImportMinimumStart.getValue();

            // Calculate if profit can be made by discharging
            if ((tariffImportMinimumPrice + tariffExportMinimumPrice) > 0) {
                continue;
            }

            long dischargeTimeMillis = tariffExportMinimum.get().getTimestamp();

            // Set the general energy level percentage targets
            for (Map.Entry<Long, Integer> entry : energyLevelPercentageTargetMap.entrySet()) {
                long timeMillis = entry.getKey();

                if (timeMillis >= startTimeMillis && timeMillis < dischargeTimeMillis) {
                    entry.setValue(100);
                } else if (timeMillis >= dischargeTimeMillis && timeMillis < endTimeMillis) {
                    entry.setValue(0);
                }
            }
        }

        return energyLevelPercentageTargetMap;
    }

    private Map<String, double[]> batteryCalculatePowerFlexibleAvailable(List<EmsElectricityBatteryAsset> electricityBatteryAssets) {
        HashMap<String, double[]> powerFlexibleAvailable = new HashMap<>();

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            boolean allowCharging = electricityBatteryAsset.getAllowCharging().orElse(false);
            boolean allowDischarging = electricityBatteryAsset.getAllowDischarging().orElse(false);
            Double energyLevelPercentage = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);
            Integer energyLevelPercentageMaximum = electricityBatteryAsset.getEnergyLevelPercentageMaximum().orElse(null);
            Integer energyLevelPercentageMinimum = electricityBatteryAsset.getEnergyLevelPercentageMinimum().orElse(null);

            double chargePowerMaximum = electricityBatteryAsset.getChargePowerMaximum().orElse(0.0);
            double dischargePowerMaximum = electricityBatteryAsset.getDischargePowerMaximum().orElse(0.0);

            if (!allowCharging || energyLevelPercentageMaximum == null || energyLevelPercentage == null) {
                chargePowerMaximum = 0.0;
            } else if (energyLevelPercentage >= energyLevelPercentageMaximum) {
                chargePowerMaximum = 0.0;
            }

            if (!allowDischarging || energyLevelPercentageMinimum == null || energyLevelPercentage == null) {
                dischargePowerMaximum = 0.0;
            } else if (energyLevelPercentage <= energyLevelPercentageMinimum) {
                dischargePowerMaximum = 0.0;
            }

            powerFlexibleAvailable.put(electricityBatteryAsset.getId(), new double[]{chargePowerMaximum, dischargePowerMaximum});
        }

        return powerFlexibleAvailable;
    }

    private Map<String, Double> batteryCalculatePowerSetpointsOnLimitBreach(Map<String, double[]> powerFlexibleAvailable, Double powerConsumptionVirtual, Double powerLimitVirtual, String maxOrMin) {
        Map<String, Double> powerSetpointsNew = new HashMap<>();
        boolean isMin = "min".equals(maxOrMin);

        // Calculate the total power adjustment needed
        double powerReduction = powerConsumptionVirtual - powerLimitVirtual;

        if (isMin) {
            powerReduction = -powerReduction;
        }

        // Iterate over each battery asset
        for (Map.Entry<String, double[]> entry : powerFlexibleAvailable.entrySet()) {
            String electricityBatteryAssetId = entry.getKey();
            double[] powerBatteryAvailableChargeDischarge = entry.getValue();

            // Choose available power based on breach type
            double powerBatteryAvailable = isMin ? -powerBatteryAvailableChargeDischarge[0] : powerBatteryAvailableChargeDischarge[1];

            if (powerReduction > 0) {
                // Limit the power set-point to available battery power or remaining reduction needed
                double setpoint = Math.round(Math.max(-powerReduction, powerBatteryAvailable) * 1000.0) / 1000.0;

                if (isMin) {
                    setpoint = -setpoint;
                }

                powerSetpointsNew.put(electricityBatteryAssetId, setpoint);
                powerReduction += setpoint;
            } else {
                powerSetpointsNew.put(electricityBatteryAssetId, 0.0);
            }
        }

        return powerSetpointsNew;
    }

    private void batteryCheckConnection(List<EmsElectricityBatteryAsset> electricityBatteryAssets, Services services) {
        // This method checks if a battery is connected based on if the power and energyLevelPercentage attributes update within the active time interval
        String connected = EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.connected.toString();
        String disconnected = EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.disconnected.toString();

        long currentTimestampMillis = services.getTimerService().getCurrentTimeMillis();
        long activePeriodMillis = ACTIVE_PERIOD_MINUTES * 60000L;

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();

            Optional<EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType> connectionStatusValue = electricityBatteryAsset.getConnectionStatus();
            String connectionStatusPrevious = "";

            if (connectionStatusValue.isPresent()) {
                connectionStatusPrevious = connectionStatusValue.get().toString();
            }

            Double powerBattery = electricityBatteryAsset.getPower().orElse(null);
            long powerTimestampMillisBattery = electricityBatteryAsset.getPowerTimestamp().orElse(0L);

            Double energyLevelPercentageBattery = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);
            long energyLevelPercentageTimestampMillisBattery = electricityBatteryAsset.getEnergyLevelPercentageTimestamp().orElse(0L);

            String connectionStatusCurrent = disconnected;

            if ((currentTimestampMillis - powerTimestampMillisBattery) < activePeriodMillis && powerBattery != null && (currentTimestampMillis - energyLevelPercentageTimestampMillisBattery) < activePeriodMillis && energyLevelPercentageBattery != null) {
                connectionStatusCurrent = connected;
            }

            if (connectionStatusCurrent.equals(connected) && connectionStatusPrevious.equals(disconnected)) {
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.CONNECTION_STATUS, EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.connected), getClass().getSimpleName());
            } else if (connectionStatusCurrent.equals(disconnected) && connectionStatusPrevious.equals(connected)) {
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.CONNECTION_STATUS, EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.disconnected), getClass().getSimpleName());
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE, null), getClass().getSimpleName());
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.POWER, null), getClass().getSimpleName());
            } else if (connectionStatusCurrent.equals(connected) && connectionStatusPrevious.isEmpty()) {
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.CONNECTION_STATUS, EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.connected), getClass().getSimpleName());
            } else if (connectionStatusCurrent.equals(disconnected) && connectionStatusPrevious.isEmpty()) {
                services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.CONNECTION_STATUS, EmsElectricityBatteryAsset.EmsElectricityBatteryConnectionStatusValueType.disconnected), getClass().getSimpleName());
            }
        }
    }

    private void batteryCheckSetup(List<EmsElectricityBatteryAsset> electricityBatteryAssets) {
        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            // Check if the battery is enabled
            boolean allowCharging = electricityBatteryAsset.getAllowDischarging().orElse(false);
            boolean allowDischarging = electricityBatteryAsset.getAllowCharging().orElse(false);

            if (!allowCharging && !allowDischarging) {
                continue;
            }

            String logPrefixBattery = String.format("assetType='%s', assetId='%s', assetName='%s'", electricityBatteryAsset.getAssetType(), electricityBatteryAsset.getId(), electricityBatteryAsset.getAssetName());

            // Check if the following attributes are connected
            Double energyLevelPercentage = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);
            Double power = electricityBatteryAsset.getPower().orElse(null);
            StringBuilder logMessageBatteryConnect = new StringBuilder();

            if (energyLevelPercentage == null) {
                logMessageBatteryConnect.append(String.format(" '%s',", EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE.getName()));
            }

            if (power == null) {
                logMessageBatteryConnect.append(String.format(" '%s',", EmsElectricityBatteryAsset.POWER.getName()));
            }

            if (!logMessageBatteryConnect.isEmpty()) {
                logMessageBatteryConnect.setLength(logMessageBatteryConnect.length() - 1);
                logMessageBatteryConnect.insert(0, String.format("%s; Can't use battery for flexible power. The following attributes are not connected:", logPrefixBattery));
                LOG.warning(logMessageBatteryConnect.toString());
            }

            // Check if the following attributes are set
            Double chargePowerMaximum = electricityBatteryAsset.getChargePowerMaximum().orElse(null);
            Double dischargePowerMaximum = electricityBatteryAsset.getDischargePowerMaximum().orElse(null);
            Integer energyLevelPercentageMaximum = electricityBatteryAsset.getEnergyLevelPercentageMaximum().orElse(null);
            Integer energyLevelPercentageMinimum = electricityBatteryAsset.getEnergyLevelPercentageMinimum().orElse(null);
            StringBuilder logMessageBatterySet = new StringBuilder();

            if (chargePowerMaximum == null) {
                logMessageBatterySet.append(String.format(" '%s',", EmsElectricityBatteryAsset.CHARGE_POWER_MAXIMUM.getName()));
            }

            if (dischargePowerMaximum == null) {
                logMessageBatterySet.append(String.format(" '%s',", EmsElectricityBatteryAsset.DISCHARGE_POWER_MAXIMUM.getName()));
            }

            if (energyLevelPercentageMaximum == null) {
                logMessageBatterySet.append(String.format(" '%s',", EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE_MAXIMUM.getName()));
            }

            if (energyLevelPercentageMinimum == null) {
                logMessageBatterySet.append(String.format(" '%s',", EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE_MINIMUM.getName()));
            }

            if (!logMessageBatterySet.isEmpty()) {
                logMessageBatterySet.setLength(logMessageBatterySet.length() - 1);
                logMessageBatterySet.insert(0, String.format("%s; Can't use battery for flexible power. The following attributes are not set:", logPrefixBattery));
                LOG.warning(logMessageBatterySet.toString());
            }

            if (energyLevelPercentageMaximum != null && energyLevelPercentageMinimum != null && (energyLevelPercentageMaximum - energyLevelPercentageMinimum) < BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL) {
                LOG.warning(String.format("%s; Can't use battery for flexible power. The (%s=%s - %s=%s < %s%%)", logPrefixBattery, EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE_MAXIMUM.getName(), energyLevelPercentageMaximum,
                        EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE_MINIMUM.getName(), energyLevelPercentageMinimum, BATTERY_ENERGY_LEVEL_PERCENTAGE_DEBOUNCE_INTERVAL));
            }
        }
    }

    private Map<String, Double> batteryCheckPowerSetpointsCurrent(List<EmsElectricityBatteryAsset> electricityBatteryAssets, Map<String, double[]> powerFlexibleAvailable) {
        Map<String, Double> powerSetpointsNew = new HashMap<>();

        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();
            Double energyLevelPercentage = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);

            if (energyLevelPercentage == null) {
                powerSetpointsNew.put(electricityBatteryAssetId, 0.0);
                continue;
            }

            double powerSetpointCurrent = electricityBatteryAsset.getPowerSetpoint().orElse(0.0);
            double chargePowerAvailable = powerFlexibleAvailable.get(electricityBatteryAssetId)[0];
            double dischargePowerAvailable = powerFlexibleAvailable.get(electricityBatteryAssetId)[1];

            // Set initial power set-point new
            double powerSetpointNew = 0.0;

            // Check if power set-point current is within available power limits
            if (powerSetpointCurrent > 0.0) {
                powerSetpointNew = Math.min(powerSetpointCurrent, chargePowerAvailable);
            } else if (powerSetpointCurrent < 0.0) {
                powerSetpointNew = Math.max(powerSetpointCurrent, dischargePowerAvailable);
            }

            powerSetpointsNew.put(electricityBatteryAssetId, powerSetpointNew);
        }

        return powerSetpointsNew;
    }

    private void batteryUpdateForecasts(EmsElectricityBatteryAsset electricityBatteryAsset, long currentTimeMillis, Map<Long, Integer> batteryEnergyLevelPercentageTargetMap, Services services) {
        Double chargePowerMaximum = electricityBatteryAsset.getChargePowerMaximum().orElse(null);
        Double dischargePowerMaximum = electricityBatteryAsset.getDischargePowerMaximum().orElse(null);
        Double energyCapacity = electricityBatteryAsset.getEnergyCapacity().orElse(null);
        Double energyLevelPercentage = electricityBatteryAsset.getEnergyLevelPercentage().orElse(null);

        if (chargePowerMaximum == null || dischargePowerMaximum == null || energyCapacity == null || energyLevelPercentage == null) {
            return;
        }

        // Calculate the battery efficiency factors
        Integer chargeEfficiency = electricityBatteryAsset.getChargeEfficiency().orElse(100);
        Integer dischargeEfficiency = electricityBatteryAsset.getDischargeEfficiency().orElse(100);

        Double chargeEfficiencyFactor = chargeEfficiency / 100.0;
        Double dischargeEfficiencyFactor = 100.0 / dischargeEfficiency;

        // Add current energy level percentage as first entry
        int energyLevelPercentageRounded = (int) Math.round(energyLevelPercentage);
        long firstEntryTimestampMillis = currentTimeMillis - 900000;
        batteryEnergyLevelPercentageTargetMap.put(firstEntryTimestampMillis, energyLevelPercentageRounded);

        // Order data-points by timestamp
        List<Map.Entry<Long, Integer>> batteryEnergyLevelPercentageTargetList = batteryEnergyLevelPercentageTargetMap.entrySet()
                .stream()
                .filter(entry -> entry.getKey() >= firstEntryTimestampMillis)
                .sorted(Map.Entry.comparingByKey())
                .toList();

        // Calculate energy level percentage forecast
        List<ValueDatapoint<?>> energyLevelPercentageForecastList = new ArrayList<>();
        energyLevelPercentageForecastList.add((new ValueDatapoint<>(firstEntryTimestampMillis, Math.round(energyLevelPercentage))));

        for (int i = 0; i < (batteryEnergyLevelPercentageTargetList.size() - 1); i++) {
            // Calculate time interval
            long targetTimestampMillis = batteryEnergyLevelPercentageTargetList.get(i).getKey();
            long targetTimestampMillisNext = batteryEnergyLevelPercentageTargetList.get(i + 1).getKey();
            long intervalMillis = targetTimestampMillisNext - targetTimestampMillis;

            // Calculate predicted energy level percentage
            long energyLevelPercentagePredicted = (long) energyLevelPercentageForecastList.get(i).getValue();
            long energyLevelPercentagePredictedNext;
            int energyLevelPercentageTargetNext = batteryEnergyLevelPercentageTargetList.get(i + 1).getValue();

            if (energyLevelPercentagePredicted > energyLevelPercentageTargetNext) {
                // Calculate discharge amount for interval
                double dischargePercentage = dischargeEfficiencyFactor * dischargePowerMaximum * intervalMillis / (energyCapacity * 36000);
                energyLevelPercentagePredictedNext = Math.round(Math.max(energyLevelPercentagePredicted + dischargePercentage, energyLevelPercentageTargetNext));
            } else {
                // Calculate charge amount for interval
                double chargePercentage = chargeEfficiencyFactor * chargePowerMaximum * intervalMillis / (energyCapacity * 36000);
                energyLevelPercentagePredictedNext = Math.round(Math.min(energyLevelPercentagePredicted + chargePercentage, energyLevelPercentageTargetNext));
            }

            long predictedTimestampMillisNext = targetTimestampMillisNext + 900000;

            energyLevelPercentageForecastList.add((new ValueDatapoint<>(predictedTimestampMillisNext, energyLevelPercentagePredictedNext)));
        }

        // Calculate power set-point forecast
        List<ValueDatapoint<?>> powerSetpointForecastList = new ArrayList<>();

        for (int i = 1; i < energyLevelPercentageForecastList.size(); i++) {
            long energyLevelPercentagePredicted = (long) energyLevelPercentageForecastList.get(i).getValue();
            long energyLevelPercentagePredictedPrevious = (long) energyLevelPercentageForecastList.get(i - 1).getValue();
            long timestampMillisPrevious = energyLevelPercentageForecastList.get(i - 1).getTimestamp();

            double powerSetpointPredicted = 0.0;

            if (energyLevelPercentagePredicted > energyLevelPercentagePredictedPrevious) {
                powerSetpointPredicted = chargePowerMaximum;
            } else if (energyLevelPercentagePredicted < energyLevelPercentagePredictedPrevious) {
                powerSetpointPredicted = dischargePowerMaximum;
            }

            powerSetpointForecastList.add((new ValueDatapoint<>(timestampMillisPrevious, powerSetpointPredicted)));
        }

        // Remove the current timestamp data-point from forecast
        energyLevelPercentageForecastList.removeFirst();
        powerSetpointForecastList.removeFirst();

        String electricityBatteryAssetId = electricityBatteryAsset.getId();

        services.getAssetPredictedDatapointService().updateValues(electricityBatteryAssetId, EmsElectricityBatteryAsset.ENERGY_LEVEL_PERCENTAGE.getName(), energyLevelPercentageForecastList);
        services.getAssetPredictedDatapointService().updateValues(electricityBatteryAssetId, EmsElectricityBatteryAsset.POWER_SETPOINT.getName(), powerSetpointForecastList);

//        System.out.print("Target energy levels: [");
//        for (Map.Entry<Long, Integer> entry : batteryEnergyLevelPercentageTargetList) {
//            System.out.print(entry.getValue() + ", ");
//        }
//        System.out.println("]");
//
//        System.out.print("Actual energy levels: [");
//        for (ValueDatapoint<?> datapoint : energyLevelPercentageForecastList) {
//            System.out.print(datapoint.getTimestamp() + ": " + datapoint.getValue() + ", ");
//        }
//        System.out.println("]");
//
//        System.out.print("Setpoint      levels: [");
//        for (ValueDatapoint<?> datapoint : powerSetpointForecastList) {
//            System.out.print(datapoint.getTimestamp() + ": " + datapoint.getValue() + ", ");
//        }
//        System.out.println("]");
    }

    private void batteryUpdatePowerSetpoints(List<EmsElectricityBatteryAsset> electricityBatteryAssets, Map<String, Double> powerSetpointsNew, Services services) {
        for (EmsElectricityBatteryAsset electricityBatteryAsset : electricityBatteryAssets) {
            String electricityBatteryAssetId = electricityBatteryAsset.getId();
            Double powerSetpointNew = powerSetpointsNew.get(electricityBatteryAssetId);

            services.getAssetProcessingService().sendAttributeEvent(new AttributeEvent(electricityBatteryAssetId, EmsElectricityBatteryAsset.POWER_SETPOINT, powerSetpointNew), getClass().getSimpleName());
        }
    }

    private EmsDayAheadAsset getDayAheadAsset(EmsEnergyOptimisationAsset energyOptimisationAsset, Services services) {
        // Find assets in database
        List<EmsDayAheadAsset> assets = services.getAssetStorageService()
                .findAll(new AssetQuery().parents(energyOptimisationAsset.getId()).types(EmsDayAheadAsset.class))
                .stream()
                .map(asset -> (EmsDayAheadAsset) asset)
                .toList();

        EmsDayAheadAsset asset = null;

        if (assets.size() == 1) {
            asset = assets.getFirst();
        } else if (assets.size() > 1) {
            String logPrefixEnergyOptimisation = String.format("assetType='%s', assetId='%s', assetName='%s'", energyOptimisationAsset.getAssetType(), energyOptimisationAsset.getId(), energyOptimisationAsset.getAssetName());
            LOG.warning(String.format("%s; Found %s '%s' assets; Only 1 '%s' asset is allowed; Remove additional '%s' assets", logPrefixEnergyOptimisation, assets.size(), EmsGOPACSAsset.class.getSimpleName(), EmsGOPACSAsset.class.getSimpleName(), EmsGOPACSAsset.class.getSimpleName()));
        }

        return asset;
    }

    private List<ValueDatapoint<?>> getTariffDayAheadDatapoints(Asset<?> asset, String attributeName, Services services) {
        // Get the start of the day (00:00) in milliseconds
        long currentTimeMillis = services.getTimerService().getCurrentTimeMillis();
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate date = Instant.ofEpochMilli(currentTimeMillis).atZone(zoneId).toLocalDate();
        long startOfCurrentDayMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli();

        // Get tariff data-points of yesterday, today and tomorrow
        long startTimeMillis = startOfCurrentDayMillis - 24 * 60 * 60000;
        long endTimeMillis = startOfCurrentDayMillis + 2 * 24 * 60 * 60000;
        AssetDatapointAllQuery assetDatapointQueryHistoric = new AssetDatapointAllQuery(startTimeMillis, endTimeMillis);
        List<ValueDatapoint<?>> tariffHistoric = services.getAssetDatapointService().queryDatapoints(asset.getId(), attributeName, assetDatapointQueryHistoric);

        return tariffHistoric;
    }

    private List<ValueDatapoint<?>> getTariffDatapoints(EmsEnergyOptimisationAsset energyOptimisationAsset, String attributeName, Services services) {
        // Get the start of the day (00:00) in milliseconds
        long currentTimeMillis = services.getTimerService().getCurrentTimeMillis();
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate date = Instant.ofEpochMilli(currentTimeMillis).atZone(zoneId).toLocalDate();
        long startOfCurrentDayMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli();

        // Get tariff data-points of yesterday and 1 week into the future
        long startTimeMillis = startOfCurrentDayMillis - 24 * 60 * 60000;
        long endTimeMillis = startOfCurrentDayMillis + 8 * 24 * 60 * 60000;
        AssetDatapointAllQuery assetDatapointQueryHistoric = new AssetDatapointAllQuery(startTimeMillis, currentTimeMillis);
        AssetDatapointAllQuery assetDatapointQueryPredicted = new AssetDatapointAllQuery(currentTimeMillis, endTimeMillis);
        List<ValueDatapoint<?>> tariffHistoric = services.getAssetDatapointService().queryDatapoints(energyOptimisationAsset.getId(), attributeName, assetDatapointQueryHistoric);
        List<ValueDatapoint<?>> tariffPredicted = services.getAssetPredictedDatapointService().queryDatapoints(energyOptimisationAsset.getId(), attributeName, assetDatapointQueryPredicted);

        // Combine historic and predicted data-points into one list
        List<ValueDatapoint<?>> tariffCombined = new ArrayList<>(tariffHistoric);
        tariffCombined.addAll(tariffPredicted);

        return tariffCombined;
    }
}

// TODO: Checked
// batteryCalculatePowerFlexibleAvailable
// batteryCalculatePowerSetpoints
// batteryCalculatePowerSetpointsOnLimitBreach
// batteryCheckConnection
// batteryCheckPowerSetpointsCurrent
// batteryCheckSetup
// batteryRestoreEnergyLevels
// batteryUpdateForecasts
// batteryUpdatePowerSetpoints
// calculateGeneralEnergyLevelPercentageTargetForecast
// getDayAheadAsset
// getTariffDayAheadDatapoints
// getTariffDatapoints


// TODO: add order to battery assets from longest to smallest discharge duration
// TODO: predict charging/discharging space + predict battery power needed per 15-minute interval for 48 hours -> Adjust energy level percentage and power set-point forecasts
