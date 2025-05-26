#!/usr/bin/env python3

import os
import json
import subprocess
import re
import time
import matplotlib.pyplot as plt

# Configuration
CONFIG_FILE = "secondSimulation/parameters/configuration.ini"
RESULTS_FILE = "simulation_results.json"  # Use the existing file
MIN_ROBOTS = 1
MAX_ROBOTS = 10  # Reduced from 30 to 10
JAVA_MAIN_CLASS = "simulator.MySimFactory"
CLASSPATH = "bin:lib/*"

def fix_config_file():
    """Fix the configuration file by ensuring all color values are in the correct format.
    This function is called when an ArrayIndexOutOfBoundsException is detected.
    """
    # Default configuration with correct color values
    default_config = """[configuration]
  display = 1
  led = 1
  simulation = 1
  mqtt = 1
  robot = 5
  obstacle = 5
  seed = 150
  field = 1
  debug = 0
  waittime = 50
  step=1200

[environment]
  rows = 20
  columns = 20

[display]
  x = 210
  y = 210
  width = 1285
  height = 650
  title = Display grid


[color]
  robot = 0,255,0
  goal = 50,50,50
  other = 255,233,0
  obstacle = 0,0,0
  unknown = 50,50,0
  package = 255,200,0
  startzone = 255,255,255
  transitzone = 0,0,255
  exit = 255,0,0
"""

    # Create a backup of the current configuration file
    backup_file = CONFIG_FILE + ".backup"
    try:
        with open(CONFIG_FILE, 'r') as src, open(backup_file, 'w') as dst:
            dst.write(src.read())
        print(f"Created backup of configuration file at {backup_file}")
    except Exception as e:
        print(f"Warning: Could not create backup: {e}")

    # Write the default configuration
    with open(CONFIG_FILE, 'w') as f:
        f.write(default_config)

    print("Configuration file has been reset to default values")

def modify_config(num_robots):
    """Modify the configuration file to set the number of robots.
    Only modifies the robot count in the [configuration] section.
    """
    with open(CONFIG_FILE, 'r') as f:
        lines = f.readlines()

    in_configuration_section = False
    modified = False

    for i, line in enumerate(lines):
        if line.strip() == "[configuration]":
            in_configuration_section = True
        elif line.strip().startswith("["):
            in_configuration_section = False

        # Only modify the robot line in the configuration section
        if in_configuration_section and line.strip().startswith("robot ="):
            lines[i] = f"  robot = {num_robots}\n"
            modified = True
            print(f"Found and modified robot line at line {i+1}")

    if not modified:
        print("Warning: Could not find robot configuration line to modify")

    with open(CONFIG_FILE, 'w') as f:
        f.writelines(lines)

    print(f"Configuration updated with {num_robots} robots")

def run_simulation():
    """Run the simulation and return the total steps taken.
    Returns None if the simulation fails or times out after 60 seconds.
    """
    cmd = ["java", "-cp", CLASSPATH, JAVA_MAIN_CLASS]

    print(f"Running simulation with command: {' '.join(cmd)}")

    try:
        # Run the simulation and capture output
        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

        # Initialize variables to track progress
        total_steps = None
        output_lines = []
        timed_out = False
        start_time = time.time()

        # Set a timeout of 60 seconds
        timeout = 60  # 1 minute timeout

        # Process output line by line as it comes with timeout
        while process.poll() is None:
            # Check if we've exceeded the timeout
            if time.time() - start_time > timeout:
                print(f"Simulation timed out after {timeout} seconds")
                timed_out = True
                # Kill the process
                try:
                    process.kill()
                    print("Process killed successfully")
                except Exception as e:
                    print(f"Error killing process: {e}")
                break

            # Try to read a line with a small timeout
            try:
                line = process.stdout.readline()
                if not line:
                    time.sleep(0.1)  # Small sleep to prevent CPU spinning
                    continue

                # Convert bytes to string if needed
                if isinstance(line, bytes):
                    line = line.decode('utf-8')

                output_lines.append(line)
                print(line.strip())  # Print the line in real-time

                # Look for the line that indicates all packages were delivered
                if "All packages delivered in" in line:
                    match = re.search(r"All packages delivered in (\d+) steps", line)
                    if match:
                        total_steps = int(match.group(1))
                        print(f"Found completion: {line.strip()}")
                        # Wait a moment to ensure data is saved
                        time.sleep(1)
                        # Try to terminate the process gracefully
                        try:
                            print("Attempting to terminate the simulation process...")
                            process.terminate()
                            # Give it a moment to terminate
                            time.sleep(1)
                            # If it's still running, force kill it
                            if process.poll() is None:
                                print("Process didn't terminate gracefully, forcing kill...")
                                process.kill()
                        except Exception as e:
                            print(f"Error terminating process: {e}")
                        break
            except Exception as e:
                print(f"Error reading output: {e}")
                time.sleep(0.1)  # Small sleep to prevent CPU spinning

        # If we timed out, mark as failed
        if timed_out:
            print("Simulation marked as failed due to timeout")
            return -1  # Special value to indicate timeout

        # If we already found the total steps, we can consider it successful
        # regardless of how the process exits
        if total_steps is not None:
            print(f"Simulation completed successfully with {total_steps} steps")
            return total_steps

        # Check if we got an ArrayIndexOutOfBoundsException, which indicates a configuration issue
        for line in output_lines:
            if "ArrayIndexOutOfBoundsException" in line and "IniFile.getColorValue" in line:
                print("Detected configuration error with color values")
                print("This is likely due to a corrupted configuration file")
                print("Attempting to fix the configuration file...")

                # Try to fix the configuration file
                try:
                    fix_config_file()
                    print("Configuration file fixed. Please run the script again.")
                except Exception as e:
                    print(f"Error fixing configuration file: {e}")

                return None

        # Wait for process to complete if it hasn't already
        if process.poll() is None:
            try:
                process.wait(timeout=5)  # Give it 5 more seconds to finish
            except subprocess.TimeoutExpired:
                process.kill()

        # Capture any stderr output
        stderr = process.stderr.read()

        if process.returncode != 0 and not timed_out:
            print(f"Simulation failed with return code {process.returncode}")
            print(f"Error output: {stderr}")
            return None

        # If we didn't find the total steps but the process completed successfully,
        # try to extract it from the output lines
        if total_steps is None and not timed_out:
            print("No completion message found in real-time output. Checking full output...")

            # Check all output lines for completion message
            for line in output_lines:
                if "All packages delivered in" in line:
                    match = re.search(r"All packages delivered in (\d+) steps", line)
                    if match:
                        total_steps = int(match.group(1))
                        print(f"Found completion in full output: {line.strip()}")
                        return total_steps

            print("No completion message found in full output. Using default max steps.")
            # If the simulation completed but we couldn't find the steps, use the maximum steps
            return 1200  # Default max steps from configuration

        return total_steps

    except Exception as e:
        print(f"Error running simulation: {e}")
        return None

def plot_results(results):
    """Plot the results to visualize the optimal number of robots."""
    # Filter out timeout results for plotting
    valid_results = [r for r in results if r['status'] == 'success']

    if not valid_results:
        print("No valid results to plot")
        return

    robots = [r['num_robots'] for r in valid_results]
    steps = [r['total_steps'] for r in valid_results]

    # Get timeout results for marking on the plot
    timeout_results = [r for r in results if r['status'] == 'failed']
    timeout_robots = [r['num_robots'] for r in timeout_results]

    plt.figure(figsize=(10, 6))

    # Plot valid results
    plt.plot(robots, steps, 'o-', linewidth=2, label='Successful Simulations')

    # Mark timeout results with red X
    if timeout_robots:
        # Use the maximum step value for visualization
        max_step = max(steps) if steps else 1200
        plt.plot(timeout_robots, [max_step] * len(timeout_robots), 'rx', markersize=10, label='Timeout (>60s)')

    plt.xlabel('Number of Robots')
    plt.ylabel('Total Steps')
    plt.title('Package Delivery Performance vs. Number of Robots')
    plt.grid(True)
    plt.legend()
    plt.savefig('robot_optimization_results.png')
    plt.close()

    # Also create an efficiency plot (steps per robot)
    efficiency = [s/r for s, r in zip(steps, robots)]

    plt.figure(figsize=(10, 6))
    plt.plot(robots, efficiency, 'o-', linewidth=2, label='Successful Simulations')

    # Mark timeout results with red X
    if timeout_robots:
        # Use the maximum efficiency value for visualization
        max_eff = max(efficiency) if efficiency else 100
        plt.plot(timeout_robots, [max_eff] * len(timeout_robots), 'rx', markersize=10, label='Timeout (>60s)')

    plt.xlabel('Number of Robots')
    plt.ylabel('Steps per Robot (lower is better)')
    plt.title('Efficiency vs. Number of Robots')
    plt.grid(True)
    plt.legend()
    plt.savefig('robot_efficiency_results.png')
    plt.close()

def main():
    # Make sure the bin directory exists
    os.makedirs("bin", exist_ok=True)

    # Compile the Java code if needed
    if not os.path.exists("bin/simulator/MySimFactory.class"):
        print("Compiling Java code...")
        compile_cmd = "javac -source 17 -target 17 -d bin -cp .:lib/* secondSimulation/simulator/*.java"
        subprocess.run(compile_cmd, shell=True, check=True)

    results = []

    # Check if we have existing results
    if os.path.exists(RESULTS_FILE):
        try:
            with open(RESULTS_FILE, 'r') as f:
                content = f.read().strip()
                if content:  # Only try to parse if file is not empty
                    results = json.loads(content)
                    print(f"Loaded {len(results)} existing results")
        except (json.JSONDecodeError, FileNotFoundError) as e:
            print(f"Error loading existing results: {e}")
            print("Starting with empty results")
            # Create an empty file if it doesn't exist or is invalid
            with open(RESULTS_FILE, 'w') as f:
                json.dump(results, f, indent=2)

    # Determine which robot counts we still need to test
    tested_robots = set(r['num_robots'] for r in results)
    robots_to_test = [n for n in range(MIN_ROBOTS, MAX_ROBOTS + 1) if n not in tested_robots]

    if not robots_to_test:
        print("All robot configurations have been tested!")
    else:
        print(f"Testing {len(robots_to_test)} robot configurations: {robots_to_test}")

    # Counter for consecutive failures
    consecutive_failures = 0

    # Run simulations for each untested robot count
    for num_robots in robots_to_test:
        print(f"\n--- Testing with {num_robots} robots ---")

        # Modify the configuration
        modify_config(num_robots)

        # Run the simulation
        total_steps = run_simulation()

        if total_steps is not None:
            if total_steps == -1:  # Timeout case
                # Record the result as a timeout
                result = {
                    'num_robots': num_robots,
                    'total_steps': 'TIMEOUT',
                    'status': 'failed',
                    'timestamp': time.strftime('%Y-%m-%d %H:%M:%S')
                }
            else:
                # Record the successful result
                result = {
                    'num_robots': num_robots,
                    'total_steps': total_steps,
                    'status': 'success',
                    'timestamp': time.strftime('%Y-%m-%d %H:%M:%S')
                }

            results.append(result)

            # Save results after each simulation
            with open(RESULTS_FILE, 'w') as f:
                json.dump(results, f, indent=2)

            print(f"Results saved to {RESULTS_FILE}")

            # Reset consecutive failures counter on success
            consecutive_failures = 0
        else:
            print(f"Skipping result for {num_robots} robots due to error")

            # If we've had multiple consecutive failures, try to fix the configuration file
            consecutive_failures += 1
            if consecutive_failures >= 2:
                print(f"Detected {consecutive_failures} consecutive failures. Attempting to fix configuration...")
                try:
                    fix_config_file()
                    print("Configuration file has been reset. Continuing with next robot count.")
                    consecutive_failures = 0  # Reset counter after fixing
                except Exception as e:
                    print(f"Error fixing configuration: {e}")
                    print("Exiting due to persistent configuration issues.")
                    break
            continue  # Skip to next robot count

    # Sort results by number of robots
    results.sort(key=lambda x: x['num_robots'])

    # Save final sorted results
    with open(RESULTS_FILE, 'w') as f:
        json.dump(results, f, indent=2)

    # Find the optimal number of robots
    if results:
        # Filter out timeout results for analysis
        valid_results = [r for r in results if r['status'] == 'success']
        timeout_results = [r for r in results if r['status'] == 'failed']

        print("\n--- Results Summary ---")
        print(f"Total configurations tested: {len(results)}")
        print(f"Successful simulations: {len(valid_results)}")
        print(f"Timed out simulations: {len(timeout_results)}")

        if valid_results:
            # Convert steps to integers for sorting
            for r in valid_results:
                if isinstance(r['total_steps'], str):
                    try:
                        r['total_steps'] = int(r['total_steps'])
                    except ValueError:
                        pass

            # Sort by total steps (ascending)
            sorted_by_steps = sorted(valid_results, key=lambda x: x['total_steps'])
            fastest = sorted_by_steps[0]

            # Sort by efficiency (steps per robot, ascending)
            sorted_by_efficiency = sorted(valid_results, key=lambda x: x['total_steps'] / x['num_robots'])
            most_efficient = sorted_by_efficiency[0]

            print(f"\nOptimal configurations:")
            print(f"Fastest delivery: {fastest['num_robots']} robots, {fastest['total_steps']} steps")
            print(f"Most efficient: {most_efficient['num_robots']} robots, {most_efficient['total_steps']} steps")
        else:
            print("\nNo successful simulations to analyze.")

        if timeout_results:
            print("\nTimed out configurations:")
            timeout_robots = [r['num_robots'] for r in timeout_results]
            print(f"Robot counts: {timeout_robots}")

        # Plot the results
        plot_results(results)
        print("Plots saved as robot_optimization_results.png and robot_efficiency_results.png")

if __name__ == "__main__":
    main()
