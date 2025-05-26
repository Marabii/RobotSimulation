#!/usr/bin/env python3

"""
Simple script to test robot configurations quickly
"""

import subprocess
import sys
import os

def run_optimization(args):
    """Run the optimization script with given arguments"""
    cmd = ["python3", "run_robot_optimization.py"] + args
    print(f"Running: {' '.join(cmd)}")
    print("-" * 50)
    
    try:
        result = subprocess.run(cmd, check=True)
        return result.returncode == 0
    except subprocess.CalledProcessError as e:
        print(f"Error running optimization: {e}")
        return False
    except KeyboardInterrupt:
        print("\nTest interrupted by user")
        return False

def main():
    if len(sys.argv) < 2:
        print("Quick Robot Testing Script")
        print("=" * 30)
        print("Usage:")
        print("  python3 test_robots.py fresh        # Clear results and run all tests")
        print("  python3 test_robots.py rerun        # Force rerun all tests")
        print("  python3 test_robots.py missing      # Run only missing tests")
        print("  python3 test_robots.py quick        # Test only 1, 5, 8 robots")
        print("  python3 test_robots.py range 3 6    # Test robots 3-6")
        print("  python3 test_robots.py specific 1 5 8  # Test specific robot counts")
        sys.exit(1)
    
    command = sys.argv[1].lower()
    
    if command == "fresh":
        print("🧹 Clearing results and running fresh tests...")
        return run_optimization(["--clear-results"])
        
    elif command == "rerun":
        print("🔄 Force rerunning all tests...")
        return run_optimization(["--force-rerun"])
        
    elif command == "missing":
        print("📋 Running only missing tests...")
        return run_optimization([])
        
    elif command == "quick":
        print("⚡ Quick test of 1, 5, 8 robots...")
        return run_optimization(["--specific-robots", "1", "5", "8"])
        
    elif command == "range":
        if len(sys.argv) < 4:
            print("Error: range command requires min and max values")
            print("Example: python3 test_robots.py range 3 6")
            sys.exit(1)
        min_robots = sys.argv[2]
        max_robots = sys.argv[3]
        print(f"📊 Testing robots {min_robots} to {max_robots}...")
        return run_optimization(["--min-robots", min_robots, "--max-robots", max_robots, "--force-rerun"])
        
    elif command == "specific":
        if len(sys.argv) < 3:
            print("Error: specific command requires robot counts")
            print("Example: python3 test_robots.py specific 1 5 8")
            sys.exit(1)
        robot_counts = sys.argv[2:]
        print(f"🎯 Testing specific robot counts: {', '.join(robot_counts)}...")
        return run_optimization(["--specific-robots"] + robot_counts)
        
    else:
        print(f"Unknown command: {command}")
        print("Use 'python3 test_robots.py' to see available commands")
        sys.exit(1)

if __name__ == "__main__":
    success = main()
    if success:
        print("\n✅ Testing completed successfully!")
    else:
        print("\n❌ Testing failed!")
        sys.exit(1)
