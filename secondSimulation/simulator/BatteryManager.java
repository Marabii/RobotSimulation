package simulator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced battery management system with load balancing and predictive
 * charging
 */
public class BatteryManager {

    private MyRobot owner;
    private List<int[]> chargingStations;
    private static Map<String, Integer> stationLoadMap = new ConcurrentHashMap<>();
    private static Map<String, Long> stationReservations = new ConcurrentHashMap<>();

    // Battery prediction parameters - balanced thresholds
    private static final double SAFETY_MARGIN = 1.5; // Reasonable safety margin
    private static final double CRITICAL_BATTERY_THRESHOLD = 0.2; // 20% of capacity
    private static final double LOW_BATTERY_THRESHOLD = 0.3; // 30% of capacity
    private static final long RESERVATION_TIMEOUT = 10000; // 10 seconds

    public BatteryManager(MyRobot owner, List<int[]> chargingStations) {
        this.owner = owner;
        this.chargingStations = chargingStations;

        // Initialize station load tracking
        for (int[] station : chargingStations) {
            String stationKey = station[0] + "," + station[1];
            stationLoadMap.putIfAbsent(stationKey, 0);
        }
    }

    /**
     * Determine if robot should head to charging station
     */
    public boolean shouldCharge() {
        double batteryRatio = (double) owner.getBatteryLevel() / owner.getBatteryCapacity();

        // Always charge if battery is below critical threshold
        if (batteryRatio <= CRITICAL_BATTERY_THRESHOLD) {
            return true;
        }

        // Check if we can reach the nearest charging station
        int[] nearestCharger = getBestChargingStation();
        if (nearestCharger != null) {
            double distanceToCharger = owner.distanceTo(nearestCharger[0], nearestCharger[1]);
            double batteryNeededToReachCharger = distanceToCharger * SAFETY_MARGIN;

            // If we can't reach the charger, charge immediately
            if (owner.getBatteryLevel() <= batteryNeededToReachCharger) {
                return true;
            }
        }

        // Low battery - check if we can complete current task
        if (batteryRatio <= LOW_BATTERY_THRESHOLD) {
            return !canCompleteCurrentTask();
        }

        // If carrying a package, only charge if we can't complete delivery
        if (owner.isCarryingPackage()) {
            return !canCompleteCurrentTask();
        }

        return false;
    }

    /**
     * Check if robot can complete its current task with remaining battery
     */
    private boolean canCompleteCurrentTask() {
        if (!owner.isCarryingPackage()) {
            return true; // No current task
        }

        double distanceToDestination = owner.distanceTo(owner.getDestX(), owner.getDestY());
        double batteryNeeded = distanceToDestination * SAFETY_MARGIN;

        return owner.getBatteryLevel() >= batteryNeeded;
    }

    /**
     * Get the best charging station considering load balancing and distance
     */
    public int[] getBestChargingStation() {
        if (chargingStations.isEmpty()) {
            return null;
        }

        cleanupExpiredReservations();

        int[] bestStation = null;
        double bestScore = Double.MAX_VALUE;

        for (int[] station : chargingStations) {
            String stationKey = station[0] + "," + station[1];

            // Calculate distance score
            double distance = owner.distanceTo(station[0], station[1]);

            // Calculate load score (prefer less crowded stations)
            int currentLoad = stationLoadMap.getOrDefault(stationKey, 0);
            double loadPenalty = currentLoad * 5.0; // Penalize crowded stations

            // Check if station is reserved
            boolean isReserved = stationReservations.containsKey(stationKey);
            double reservationPenalty = isReserved ? 10.0 : 0.0;

            // Calculate total score (lower is better)
            double totalScore = distance + loadPenalty + reservationPenalty;

            if (totalScore < bestScore) {
                bestScore = totalScore;
                bestStation = station;
            }
        }

        return bestStation;
    }

    /**
     * Reserve a charging station
     */
    public void reserveChargingStation(int[] station) {
        String stationKey = station[0] + "," + station[1];
        stationReservations.put(stationKey, System.currentTimeMillis());

        // Increment load counter
        stationLoadMap.merge(stationKey, 1, Integer::sum);
    }

    /**
     * Release reservation for a charging station
     */
    public void releaseChargingStation(int[] station) {
        String stationKey = station[0] + "," + station[1];
        stationReservations.remove(stationKey);

        // Decrement load counter
        stationLoadMap.merge(stationKey, -1, Integer::sum);

        // Ensure load doesn't go negative
        if (stationLoadMap.get(stationKey) < 0) {
            stationLoadMap.put(stationKey, 0);
        }
    }

    /**
     * Clean up expired reservations
     */
    private void cleanupExpiredReservations() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = stationReservations.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > RESERVATION_TIMEOUT) {
                iterator.remove();

                // Also decrement load counter
                stationLoadMap.merge(entry.getKey(), -1, Integer::sum);
                if (stationLoadMap.get(entry.getKey()) < 0) {
                    stationLoadMap.put(entry.getKey(), 0);
                }
            }
        }
    }

    /**
     * Get charging station with specific approach pattern for load distribution
     */
    public int[] getChargingApproachPosition(int[] station) {
        int stationX = station[0];
        int stationY = station[1];

        // Use robot ID to determine approach direction for better distribution
        int robotId = owner.getId();
        int approachDirection = robotId % 8;

        int targetX = stationX;
        int targetY = stationY;

        switch (approachDirection) {
            case 0: // North
                targetX = stationX - 1;
                break;
            case 1: // Northeast
                targetX = stationX - 1;
                targetY = stationY + 1;
                break;
            case 2: // East
                targetY = stationY + 1;
                break;
            case 3: // Southeast
                targetX = stationX + 1;
                targetY = stationY + 1;
                break;
            case 4: // South
                targetX = stationX + 1;
                break;
            case 5: // Southwest
                targetX = stationX + 1;
                targetY = stationY - 1;
                break;
            case 6: // West
                targetY = stationY - 1;
                break;
            case 7: // Northwest
                targetX = stationX - 1;
                targetY = stationY - 1;
                break;
        }

        // Ensure target is within grid bounds
        targetX = Math.max(0, Math.min(owner.getGridRows() - 1, targetX));
        targetY = Math.max(0, Math.min(owner.getGridColumns() - 1, targetY));

        return new int[] { targetX, targetY };
    }

    /**
     * Get current battery status as a descriptive string
     */
    public String getBatteryStatus() {
        double batteryRatio = (double) owner.getBatteryLevel() / owner.getBatteryCapacity();

        if (batteryRatio <= CRITICAL_BATTERY_THRESHOLD) {
            return "CRITICAL";
        } else if (batteryRatio <= LOW_BATTERY_THRESHOLD) {
            return "LOW";
        } else if (batteryRatio <= 0.7) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }

    /**
     * Get estimated battery needed for a task
     */
    public double estimateBatteryNeeded(int targetX, int targetY) {
        double distance = owner.distanceTo(targetX, targetY);
        return distance * SAFETY_MARGIN;
    }
}
