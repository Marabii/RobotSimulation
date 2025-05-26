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

    // Battery prediction parameters - dynamic management
    private static final double SAFETY_MARGIN = 1.3; // Reasonable safety margin for route planning
    private static final double LOW_BATTERY_THRESHOLD = 0.20; // 20% of capacity (for optimization only)
    private static final double FULL_CHARGE_THRESHOLD = 0.90; // 90% considered "full" for leaving stations
    private static final long RESERVATION_TIMEOUT = 10000; // 10 seconds

    // Dynamic route validation parameters
    private static final double CALCULATION_ERROR_BUFFER = 1.02; // 2% buffer for calculation errors only
    private static final double ROUTE_SAFETY_BUFFER = 1.15; // Modest buffer for route validation
    private static final double CHARGING_RETURN_BUFFER = 1.25; // Buffer for returning to charging station

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
     * Enhanced with full route validation and safety checks
     */
    public boolean shouldCharge() {
        double batteryRatio = (double) owner.getBatteryLevel() / owner.getBatteryCapacity();

        // Never leave charging station unless fully charged
        if (owner.onChargingZone() && batteryRatio < FULL_CHARGE_THRESHOLD) {
            return true;
        }

        // Dynamic reachability calculation - can we reach ANY charging station?
        if (!canReachAnyChargingStationDynamic()) {
            System.out.println(owner.getName()
                    + " cannot reach any charging station from current position - must charge immediately");
            return true;
        }

        // If carrying a package, check if we can complete delivery AND reach a charging
        // station
        if (owner.isCarryingPackage()) {
            boolean canCompleteDelivery = canCompleteDeliveryAndReachCharging();

            // If we can't complete delivery, try to find a robot for handoff before
            // charging
            if (!canCompleteDelivery) {
                if (shouldAttemptHandoff()) {
                    return false; // Don't charge yet, attempt handoff first
                }
            }

            return !canCompleteDelivery;
        }

        // For free robots, use low battery threshold as optimization hint (not
        // requirement)
        if (batteryRatio <= LOW_BATTERY_THRESHOLD) {
            // Even at low battery, only charge if we can't complete a potential task
            return !canCompleteAnyPotentialTask();
        }

        return false;
    }

    /**
     * Dynamic reachability check - can robot reach ANY charging station with
     * minimal buffer
     */
    private boolean canReachAnyChargingStationDynamic() {
        for (int[] station : chargingStations) {
            double distance = owner.distanceTo(station[0], station[1]);
            double batteryNeeded = distance * CALCULATION_ERROR_BUFFER; // Only 5% buffer for calculation errors

            if (owner.getBatteryLevel() >= batteryNeeded) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if robot can complete delivery and reach a charging station afterward
     */
    private boolean canCompleteDeliveryAndReachCharging() {
        if (!owner.isCarryingPackage()) {
            return true; // No current task
        }

        // First priority: Can we complete the delivery?
        double distanceToDestination = owner.distanceTo(owner.getDestX(), owner.getDestY());
        // For committed robots (carrying packages), use direct distance without buffer
        // They're already committed and should attempt delivery
        double batteryForDelivery = distanceToDestination; // No buffer - use exact distance

        // Only charge if we definitely can't reach destination (with some tolerance for
        // rounding)
        if (owner.getBatteryLevel() < (batteryForDelivery - 0.5)) { // Allow 0.5 battery tolerance
            System.out.println(owner.getName() + " definitely cannot complete delivery (need " +
                    batteryForDelivery + ", have " + owner.getBatteryLevel() + ") - must charge");
            return false;
        }

        // If we can complete delivery, do it! Don't worry about post-delivery charging
        // yet
        // The robot can figure out charging after successful delivery
        return true;
    }

    /**
     * Check if robot can complete any potential task (for free robots)
     */
    private boolean canCompleteAnyPotentialTask() {
        // For free robots, check if they can at least move around and reach charging
        // This is a simplified check - if they can reach a charging station, they can
        // do basic tasks
        return canReachAnyChargingStationDynamic();
    }

    /**
     * Validate if robot can complete a full route (pickup -> delivery -> return to
     * charge)
     * before committing to the task
     */
    public boolean canCompleteFullRoute(int pickupX, int pickupY, int destinationGoalId) {
        // Get destination coordinates
        int[] goalPos = owner.getGoalPosition(destinationGoalId);
        if (goalPos == null) {
            return false; // Invalid destination
        }

        // Calculate battery needed for pickup
        double distanceToPickup = owner.distanceTo(pickupX, pickupY);
        double batteryForPickup = distanceToPickup * ROUTE_SAFETY_BUFFER;

        // Calculate battery needed for delivery
        double distanceToDelivery = calculateDistance(pickupX, pickupY, goalPos[0], goalPos[1]);
        double batteryForDelivery = distanceToDelivery * ROUTE_SAFETY_BUFFER;

        // Calculate battery needed to return to charging station from delivery point
        int[] nearestChargerFromDest = findNearestChargingStationFrom(goalPos[0], goalPos[1]);
        if (nearestChargerFromDest == null) {
            return false; // No charging station available
        }

        double distanceToChargerFromDest = calculateDistance(goalPos[0], goalPos[1],
                nearestChargerFromDest[0], nearestChargerFromDest[1]);
        double batteryForReturn = distanceToChargerFromDest * CHARGING_RETURN_BUFFER;

        // Total battery needed for complete route
        double totalBatteryNeeded = batteryForPickup + batteryForDelivery + batteryForReturn;

        boolean canComplete = owner.getBatteryLevel() >= totalBatteryNeeded;

        if (!canComplete) {
            System.out.println(owner.getName() + " route validation failed: need " + totalBatteryNeeded +
                    " battery, have " + owner.getBatteryLevel());
        }

        return canComplete;
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

        // Dynamic status based on reachability rather than fixed thresholds
        if (!canReachAnyChargingStationDynamic()) {
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

    /**
     * Find the nearest charging station from a specific position
     */
    private int[] findNearestChargingStationFrom(int x, int y) {
        int[] nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (int[] station : chargingStations) {
            double d = calculateDistance(x, y, station[0], station[1]);
            if (d < bestDist) {
                bestDist = d;
                nearest = station;
            }
        }
        return nearest;
    }

    /**
     * Calculate distance between two points
     */
    private double calculateDistance(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow((x2 - x1), 2) + Math.pow((y2 - y1), 2));
    }

    /**
     * Find alternative path to charging station if primary route is unavailable
     * This method attempts to find the best available charging station considering
     * current obstacles and robot positions
     */
    public int[] findAlternativeChargingPath() {
        cleanupExpiredReservations();

        // Try to find a charging station that's not heavily loaded
        int[] bestStation = null;
        double bestScore = Double.MAX_VALUE;

        for (int[] station : chargingStations) {
            String stationKey = station[0] + "," + station[1];

            // Calculate distance score
            double distance = owner.distanceTo(station[0], station[1]);

            // Calculate load score (heavily penalize crowded stations)
            int currentLoad = stationLoadMap.getOrDefault(stationKey, 0);
            double loadPenalty = currentLoad * 8.0; // Higher penalty for alternative paths

            // Check if station is reserved
            boolean isReserved = stationReservations.containsKey(stationKey);
            double reservationPenalty = isReserved ? 15.0 : 0.0;

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
     * Check if robot has sufficient battery to reach any charging station
     * Used as a safety check before starting any task
     */
    public boolean canReachAnyChargingStation() {
        for (int[] station : chargingStations) {
            double distance = owner.distanceTo(station[0], station[1]);
            double batteryNeeded = distance * CHARGING_RETURN_BUFFER;

            if (owner.getBatteryLevel() >= batteryNeeded) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if robot should attempt package handoff to another robot
     */
    private boolean shouldAttemptHandoff() {
        if (!owner.isCarryingPackage()) {
            return false;
        }

        // Only attempt handoff if we have low battery and can't complete delivery
        double batteryRatio = (double) owner.getBatteryLevel() / owner.getBatteryCapacity();
        if (batteryRatio > LOW_BATTERY_THRESHOLD) {
            return false; // Battery is still good
        }

        // Check if there are other robots available for handoff
        return owner.findSuitableHandoffRobot() != null;
    }
}
