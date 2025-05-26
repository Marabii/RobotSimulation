package simulator;

import fr.emse.fayol.maqit.simulator.components.ColorStartZone;
import fr.emse.fayol.maqit.simulator.components.ColorTransitZone;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.PackageState;
import fr.emse.fayol.maqit.simulator.environment.Cell;
import fr.emse.fayol.maqit.simulator.environment.ColorCell;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;

import java.awt.Color;
import java.util.List;

public class MyTransitRobot extends MyRobot {

    private List<int[]> transitZoneData;
    private boolean hasDroppedInTransitZone = false;

    public MyTransitRobot(String name, int field, int debug, int[] pos, Color color,
            int rows, int columns, ColorGridEnvironment env, long seed,
            List<int[]> startZoneList,
            List<int[]> transitZoneData,
            List<int[]> chargingStations // *** ADDED
    ) {
        // Pass the same chargingStations to super
        super(name, field, debug, pos, color, rows, columns, env, seed,
                startZoneList, chargingStations);
        this.transitZoneData = transitZoneData;
    }

    private ColorTransitZone findAvailableTransitZone() {
        for (int[] pos : transitZoneData) {
            Cell c = env.getGrid()[pos[0]][pos[1]];
            if (c instanceof ColorCell && c.getContent() instanceof ColorTransitZone) {
                ColorTransitZone tz = (ColorTransitZone) c.getContent();
                if (!tz.isFull()) {
                    return tz;
                }
            }
        }
        return null;
    }

    private ColorTransitZone findTransitZoneWithPackage() {
        for (int[] pos : transitZoneData) {
            Cell c = env.getGrid()[pos[0]][pos[1]];
            if (c instanceof ColorCell && c.getContent() instanceof ColorTransitZone) {
                ColorTransitZone tz = (ColorTransitZone) c.getContent();
                if (!tz.getPackages().isEmpty()) {
                    return tz;
                }
            }
        }
        return null;
    }

    @Override
    public void step() {
        // Battery housekeeping & skip if recharging
        manageBattery();
        if (isRecharging || batteryLevel <= 0) {
            return;
        }

        // Periodic coordination tasks
        long currentTime = System.currentTimeMillis();

        // Broadcast status periodically
        if (currentTime - lastStatusBroadcast > STATUS_BROADCAST_INTERVAL) {
            coordinator.broadcastStatus();
            lastStatusBroadcast = currentTime;
        }

        // Run coordination checks periodically
        if (currentTime - lastCoordinationCheck > COORDINATION_CHECK_INTERVAL) {
            // Process any active auctions
            coordinator.processAuctions();

            // Consider package handoffs if carrying a package
            if (isCarryingPackage()) {
                coordinator.considerHandoffs();
            }

            // Clean up expired information
            coordinator.cleanupExpiredInfo();

            lastCoordinationCheck = currentTime;
        }

        // Handle handoff logic if active
        if (handleHandoffLogic()) {
            return; // Skip regular logic if handling a handoff
        }

        // Also do the proactive check for low battery
        int[] nearestCharger = getNearestChargingStation();
        if (nearestCharger != null) {
            double distToCharger = distanceTo(nearestCharger[0], nearestCharger[1]);
            // Add a larger safety margin (6 instead of 3) to ensure we can reach the
            // station - doubled to match the doubled battery capacity
            // This helps prevent robots from running out of battery
            if (batteryLevel <= distToCharger + 6) {
                System.out.println(getName() + " battery is low! Heading to charge... Battery: " + batteryLevel
                        + ", Distance to charger: " + distToCharger);
                goCharge();
                return;
            }
        }

        if (etat == Etat.DELIVRE)
            return;

        // If no package, see if there's one in a transit zone
        if (etat == Etat.FREE) {
            ColorTransitZone tz = findTransitZoneWithPackage();
            if (tz != null && isAdjacentTo(tz.getX(), tz.getY())) {
                if (!tz.getPackages().isEmpty()) {
                    carriedPackage = tz.getPackages().get(0);
                    tz.removePackage(carriedPackage);
                    System.out.println(getName() + " took a package from Transit Zone.");
                    int[] goalPos = GOALS.get(carriedPackage.getDestinationGoalId());
                    if (goalPos != null) {
                        destX = goalPos[0];
                        destY = goalPos[1];
                        etat = Etat.TRANSPORT;
                    }
                    tempsDepart = System.currentTimeMillis();

                    // Announce this task to the coordination system
                    coordinator.announceTask(
                            carriedPackage.getId(),
                            carriedPackage.getStartZone(),
                            carriedPackage.getDestinationGoalId(),
                            1.5 // higher urgency for transit zone packages
                    );
                }
            } else if (tz != null) {
                moveOneStepTo(tz.getX(), tz.getY());
            } else {
                // fallback: use parent's logic (pick from start zone)
                ColorStartZone zone = findStartZoneWithPackage();
                if (zone == null)
                    return;
                if (isAdjacentTo(zone.getX(), zone.getY())) {
                    if (!zone.getPackages().isEmpty()) {
                        carriedPackage = zone.getPackages().get(0);
                        zone.removePackage(carriedPackage);
                        System.out.println(getName() + " took a package from Start Zone.");
                        int[] goalPos = GOALS.get(carriedPackage.getDestinationGoalId());
                        if (goalPos != null) {
                            destX = goalPos[0];
                            destY = goalPos[1];
                            etat = Etat.TRANSPORT;
                        }
                        tempsDepart = System.currentTimeMillis();

                        // Announce this task to the coordination system
                        coordinator.announceTask(
                                carriedPackage.getId(),
                                carriedPackage.getStartZone(),
                                carriedPackage.getDestinationGoalId(),
                                1.0 // default urgency
                        );
                    }
                } else {
                    moveOneStepTo(zone.getX(), zone.getY());
                }
            }
        } else if (etat == Etat.TRANSPORT) {
            if (this.getX() == destX && this.getY() == destY) {
                // deliver
                carriedPackage.setState(PackageState.ARRIVED);
                MySimFactory.deliveredCount++;
                tempsArrivee = System.currentTimeMillis();
                etat = Etat.DELIVRE;
                env.removeCellContent(this.getX(), this.getY());
                System.out.println(getName() + " delivered a package and disappears.");
            } else {
                // Possibly drop in a transit zone first
                if (!hasDroppedInTransitZone) {
                    ColorTransitZone freeTz = findAvailableTransitZone();
                    if (freeTz != null) {
                        if (isAdjacentTo(freeTz.getX(), freeTz.getY())) {
                            // drop
                            freeTz.addPackage(carriedPackage);
                            carriedPackage = null;
                            etat = Etat.FREE;
                            hasDroppedInTransitZone = true;
                            System.out.println(getName() + " dropped the package in a Transit Zone.");
                            return;
                        } else {
                            moveOneStepTo(freeTz.getX(), freeTz.getY());
                            return;
                        }
                    } else {
                        broadcastMessage("No free Transit Zone available!");
                    }
                }
                // Otherwise continue to final goal
                moveOneStepTo(destX, destY);
            }
        }
    }

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);
        if (msg.getContent().contains("No free Transit Zone")) {
            System.out.println(getName() + " got the broadcast about no free zone. Will deliver directly...");
        }
    }
}
