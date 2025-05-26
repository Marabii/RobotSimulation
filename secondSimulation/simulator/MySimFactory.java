package simulator;

import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;
import fr.emse.fayol.maqit.simulator.components.ColorExitZone;
import fr.emse.fayol.maqit.simulator.components.ColorObstacle;
import fr.emse.fayol.maqit.simulator.components.ColorPackage;
import fr.emse.fayol.maqit.simulator.components.ColorStartZone;
import fr.emse.fayol.maqit.simulator.components.ColorTransitZone;
import fr.emse.fayol.maqit.simulator.components.Robot;
import fr.emse.fayol.maqit.simulator.environment.Cell;
import fr.emse.fayol.maqit.simulator.environment.ColorCell;
import fr.emse.fayol.maqit.simulator.environment.ColorGoal;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;

import java.awt.Color;
import java.util.*;

/**
 * Manages the main simulation scenario:
 * environment creation, obstacles, packages, robots,
 * plus scheduling their moves.
 */
public class MySimFactory extends SimFactory {

    private Map<String, ColorStartZone> startZonesMap = new HashMap<>();

    public static int deliveredCount = 0; // count how many packets are delivered

    int nbPackages;
    int nbNotGeneratedPackets;
    int numberOfWorkers;
    Random rnd;
    int totalSteps = 0;

    public MySimFactory(SimProperties sp) {
        super(sp);
    }

    @Override
    public void createEnvironment() {
        environment = new ColorGridEnvironment(sp.rows, sp.columns, sp.debug, sp.seed);
        environment.initializeGrid();
    }

    @Override
    public void createObstacle() {
        for (int[] pos : sp.obstaclePositions) {
            ColorObstacle obstacle = new ColorObstacle(
                    pos,
                    new int[] {
                            sp.colorobstacle.getRed(),
                            sp.colorobstacle.getGreen(),
                            sp.colorobstacle.getBlue()
                    });
            addNewComponent(obstacle);
        }
    }

    @Override
    public void createGoal() {
        int[] z1Pos = sp.goalPositions.get(1);
        int[] z2Pos = sp.goalPositions.get(2);

        // Z1
        ((ColorCell) environment.getGrid()[z1Pos[0]][z1Pos[1]])
                .setGoal(new ColorGoal(
                        1,
                        new int[] {
                                sp.colorgoal.getRed(),
                                sp.colorgoal.getGreen(),
                                sp.colorgoal.getBlue()
                        }));
        // Z2
        ((ColorCell) environment.getGrid()[z2Pos[0]][z2Pos[1]])
                .setGoal(new ColorGoal(
                        2,
                        new int[] {
                                sp.colorgoal.getRed(),
                                sp.colorgoal.getGreen(),
                                sp.colorgoal.getBlue()
                        }));
    }

    /**
     * Create some packages in start zones
     */
    public void createPackages(int nbpackages) {
        String[] startZones = { "A1", "A2", "A3" };
        for (int i = 0; i < nbpackages; i++) {
            int destinationId = rnd.nextInt(2) + 1; // 1 or 2
            int ts = 0; // departure time
            int randomStartZone = rnd.nextInt(startZones.length);
            String zone = startZones[randomStartZone];

            int[] position = { -1, -1 }; // no real position in the grid

            ColorPackage pack = new ColorPackage(
                    position,
                    new int[] {
                            sp.colorpackage.getRed(),
                            sp.colorpackage.getGreen(),
                            sp.colorpackage.getBlue()
                    },
                    destinationId,
                    ts,
                    zone);

            ColorStartZone startZone = getStartZoneById(zone);
            if (startZone != null) {
                startZone.addPackage(pack);
            } else {
                System.out.println("StartZone " + zone + " does not exist!");
            }
        }
    }

    public ColorStartZone getStartZoneById(String id) {
        return startZonesMap.get(id);
    }

    public void createStartZones() {
        for (Map.Entry<String, int[]> entry : sp.startZonePositions.entrySet()) {
            String zoneId = entry.getKey();
            int[] pos = entry.getValue();

            ColorStartZone zone = new ColorStartZone(
                    pos,
                    new int[] {
                            sp.colorstartzone.getRed(),
                            sp.colorstartzone.getGreen(),
                            sp.colorstartzone.getBlue()
                    });

            addNewComponent(zone);
            startZonesMap.put(zoneId, zone);
        }
    }

    public void createTransitZones() {
        for (int[] data : sp.transitZoneData) {
            int x = data[0];
            int y = data[1];
            int capacity = data[2];
            ColorTransitZone tz = new ColorTransitZone(
                    new int[] { x, y },
                    new int[] {
                            sp.colortransitzone.getRed(),
                            sp.colortransitzone.getGreen(),
                            sp.colortransitzone.getBlue()
                    },
                    capacity);
            addNewComponent(tz);
        }
    }

    public void createExitZones() {
        for (int[] pos : sp.exitZonePositions) {
            ColorExitZone exitZone = new ColorExitZone(
                    pos,
                    new int[] {
                            sp.colorexit.getRed(),
                            sp.colorexit.getGreen(),
                            sp.colorexit.getBlue()
                    });
            addNewComponent(exitZone);
        }
    }

    public void createWorker() {
        for (int i = 0; i < numberOfWorkers; i++) {
            int[] pos = environment.getPlace();
            Worker worker = new Worker(
                    "Worker" + i,
                    sp.field,
                    sp.debug,
                    pos,
                    new Color(sp.colorother.getRed(), sp.colorother.getGreen(), sp.colorother.getBlue()),
                    sp.rows,
                    sp.columns,
                    sp.seed);
            addNewComponent(worker);
        }
    }

    // *** ADDED or CHANGED *** (Multiple charging stations)
    /**
     * Creates multiple charging zones from sp.chargingZonePositions.
     */
    public void createChargingZones() {
        List<int[]> chargingZonePositions = List.of(new int[] { 5, 5 }, new int[] { 4, 10 });

        for (int[] cpos : chargingZonePositions) {
            ColorChargingZone cz = new ColorChargingZone(
                    cpos,
                    new int[] { 255, 0, 255 } // e.g. magenta
            );
            addNewComponent(cz);
        }
    }

    @Override
    public void createRobot() {
        if (environment == null) {
            throw new IllegalStateException("Environment must be created before adding robots!");
        }

        // Convert start-zone positions to a List
        List<int[]> startZoneList = new ArrayList<>(sp.startZonePositions.values());

        List<int[]> chargingZonePositions = List.of(new int[] { 5, 5 }, new int[] { 4, 10 });

        for (int i = 0; i < sp.nbrobot; i++) {
            int[] pos = environment.getPlace();
            MyRobot robot = new MyRobot(
                    "Robot" + i,
                    sp.field,
                    sp.debug,
                    pos,
                    new Color(sp.colorrobot.getRed(), sp.colorrobot.getGreen(), sp.colorrobot.getBlue()),
                    sp.rows,
                    sp.columns,
                    (ColorGridEnvironment) environment,
                    sp.seed,
                    startZoneList,
                    chargingZonePositions);
            addNewComponent(robot);
        }
    }

    @Override
    public void schedule() {
        List<Robot> robots = environment.getRobot();
        int currentNBPacket;

        for (int i = 0; i < sp.step; i++) {
            totalSteps++;

            // Packet creation over time
            if (nbNotGeneratedPackets > 0 && validGeneration()) {
                if (nbNotGeneratedPackets > 2)
                    currentNBPacket = rnd.nextInt(nbNotGeneratedPackets / 2 + 1);
                else
                    currentNBPacket = 2;
                createPackages(currentNBPacket);
                nbNotGeneratedPackets -= currentNBPacket;
            }

            // Activate robots
            for (Robot r : robots) {
                int[] prevPos = r.getLocation();
                Cell[][] perception = environment.getNeighbor(r.getX(), r.getY(), r.getField());
                r.updatePerception(perception);

                if (r instanceof MyTransitRobot) {
                    ((MyTransitRobot) r).step();
                } else if (r instanceof MyRobot) {
                    ((MyRobot) r).step();
                } else {
                    r.move(1);
                }

                updateEnvironment(prevPos, r.getLocation());
            }

            refreshGW();

            // Check if all packages are delivered
            if (MySimFactory.deliveredCount >= nbPackages) {
                System.out.println("All packages delivered in " + totalSteps + " steps.");
                break;
            }

            try {
                Thread.sleep(sp.waittime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean validGeneration() {
        // Simple rule: every 10 steps, generate new packages
        return (totalSteps % 10 == 0);
    }

    public static void main(String[] args) throws Exception {
        // Load main config
        IniFile ifile = new IniFile("secondSimulation/parameters/configuration.ini");
        IniFile ifilenv = new IniFile("secondSimulation/parameters/environment.ini");

        // Simulation properties
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();

        // Environment properties
        SimProperties envProp = new SimProperties(ifilenv);
        envProp.loadObstaclePositions();
        envProp.loadStartZonePositions();
        envProp.loadTransitZones();
        envProp.loadExitZonePositions();
        envProp.loadGoalPositions();

        // Merge environment info
        sp.obstaclePositions = envProp.obstaclePositions;
        sp.startZonePositions = envProp.startZonePositions;
        sp.transitZoneData = envProp.transitZoneData;
        sp.exitZonePositions = envProp.exitZonePositions;
        sp.goalPositions = envProp.goalPositions;

        System.out.println("Environment size: " + sp.rows + "x" + sp.columns);

        MySimFactory sim = new MySimFactory(sp);

        // We decide how many packages in total
        sim.nbPackages = sp.nbrobot;
        sim.nbNotGeneratedPackets = sim.nbPackages;

        // numberOfWorkers to place
        sim.numberOfWorkers = sp.nbobstacle / 2;
        sim.rnd = new Random(sp.seed);

        // Build environment
        sim.createEnvironment();
        sim.createObstacle();
        sim.createGoal();
        sim.createStartZones();
        sim.createTransitZones();
        sim.createExitZones();

        // Create multiple charging zones
        sim.createChargingZones();

        // Then create workers & robots
        sim.createWorker();
        sim.createRobot();

        // Initialize & run
        sim.initializeGW();
        sim.schedule();
    }
}
