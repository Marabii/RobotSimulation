#!/usr/bin/env python3

"""
Debug script to understand what's happening in the simulation
"""

import subprocess
import time

def run_debug_simulation():
    """Run a single simulation with debug output"""
    print("🔍 Running debug simulation...")
    print("=" * 50)

    # Compile first
    print("Compiling...")
    compile_result = subprocess.run(
        "javac --release 11 -d bin -cp .:lib/* secondSimulation/simulator/*.java",
        shell=True, capture_output=True, text=True
    )

    if compile_result.returncode != 0:
        print(f"❌ Compilation failed: {compile_result.stderr}")
        return

    print("✅ Compilation successful")

    # Run simulation with timeout and capture output
    print("\n🚀 Starting simulation...")
    cmd = "timeout 15 java -cp bin:lib/* simulator.MySimFactory"

    start_time = time.time()
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    end_time = time.time()

    print(f"⏱️  Simulation ran for {end_time - start_time:.2f} seconds")
    print(f"📊 Return code: {result.returncode}")

    if result.stdout:
        print("\n📝 Output:")
        print("-" * 30)
        lines = result.stdout.split('\n')

        # Show key events in order
        key_events = []
        for i, line in enumerate(lines):
            if any(keyword in line.lower() for keyword in ['took a package', 'moving from', 'battery', 'path', 'charge', 'delivered', 'stuck']):
                key_events.append(f"{i+1:3d}: {line}")

        # Show first 30 key events
        for event in key_events[:30]:
            print(event)
        if len(key_events) > 30:
            print(f"... and {len(key_events) - 30} more key events")

        print(f"\nTotal lines: {len(lines)}, Key events: {len(key_events)}")

    if result.stderr:
        print("\n❌ Errors:")
        print("-" * 30)
        print(result.stderr)

    # Analyze the output
    print("\n🔍 Analysis:")
    print("-" * 30)

    if "Starting simulation" in result.stdout:
        print("✅ Simulation started successfully")
    else:
        print("❌ Simulation failed to start")

    if "delivered" in result.stdout.lower():
        print("✅ Some packages were delivered")
    else:
        print("⚠️  No packages delivered")

    if "cannot reach any charging station" in result.stdout:
        print("⚠️  Robots had charging station access issues")

    if "route validation failed" in result.stdout:
        print("⚠️  Route validation was too strict")

    if "battery-safe path" in result.stdout:
        print("✅ Battery-aware pathfinding is working")

    # Count key events
    took_package_count = result.stdout.count("took a package")
    delivered_count = result.stdout.count("delivered")
    charging_count = result.stdout.count("starts recharging")

    print(f"📦 Packages picked up: {took_package_count}")
    print(f"🎯 Packages delivered: {delivered_count}")
    print(f"🔋 Charging events: {charging_count}")

if __name__ == "__main__":
    run_debug_simulation()
