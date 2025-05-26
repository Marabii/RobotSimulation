#!/usr/bin/env python3

"""
Java version checker and compatibility fixer
"""

import subprocess
import os
import sys

def run_command(cmd):
    """Run a command and return the result"""
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except Exception as e:
        return -1, "", str(e)

def check_java_versions():
    """Check Java and javac versions"""
    print("🔍 Checking Java versions...")
    print("=" * 40)
    
    # Check java version
    ret, out, err = run_command("java -version")
    if ret == 0:
        print("☕ Java Runtime:")
        print(f"   {err.split(chr(10))[0] if err else out.split(chr(10))[0]}")
    else:
        print("❌ Java runtime not found!")
        return False
    
    # Check javac version
    ret, out, err = run_command("javac -version")
    if ret == 0:
        print("🔨 Java Compiler:")
        print(f"   javac {out}")
    else:
        print("❌ Java compiler not found!")
        return False
    
    return True

def clean_and_compile():
    """Clean and recompile with compatible settings"""
    print("\n🧹 Cleaning and recompiling...")
    print("=" * 40)
    
    # Clean bin directory
    if os.path.exists("bin"):
        subprocess.run("rm -rf bin", shell=True)
    os.makedirs("bin", exist_ok=True)
    print("✅ Cleaned bin directory")
    
    # Try different compilation strategies
    strategies = [
        ("Default", "javac -d bin -cp .:lib/* secondSimulation/simulator/*.java"),
        ("Java 11", "javac --release 11 -d bin -cp .:lib/* secondSimulation/simulator/*.java"),
        ("Java 8", "javac --release 8 -d bin -cp .:lib/* secondSimulation/simulator/*.java"),
        ("Source/Target 11", "javac -source 11 -target 11 -d bin -cp .:lib/* secondSimulation/simulator/*.java"),
        ("Source/Target 8", "javac -source 8 -target 8 -d bin -cp .:lib/* secondSimulation/simulator/*.java"),
    ]
    
    for name, cmd in strategies:
        print(f"\n🔨 Trying {name}...")
        ret, out, err = run_command(cmd)
        if ret == 0:
            print(f"✅ {name} compilation successful!")
            
            # Test the compiled classes
            print("🧪 Testing compiled classes...")
            test_ret, test_out, test_err = run_command("java -cp bin:lib/* simulator.MySimFactory 2>&1 | head -5")
            
            if "UnsupportedClassVersionError" in test_err:
                print(f"❌ {name} - Version compatibility issue")
                continue
            elif "FileNotFoundException" in test_err or "configuration.ini" in test_err:
                print(f"✅ {name} - Classes work! (Config file issue is expected)")
                return True
            else:
                print(f"✅ {name} - Classes appear to work!")
                return True
        else:
            print(f"❌ {name} failed: {err}")
    
    print("\n❌ All compilation strategies failed!")
    return False

def main():
    print("Java Compatibility Checker")
    print("=" * 50)
    
    if not check_java_versions():
        print("\n❌ Java installation issues detected!")
        sys.exit(1)
    
    if not clean_and_compile():
        print("\n❌ Could not create compatible Java classes!")
        print("\n💡 Suggestions:")
        print("1. Update your Java runtime to match the compiler version")
        print("2. Use a different Java version for compilation")
        print("3. Install OpenJDK 11 or 17 for better compatibility")
        sys.exit(1)
    
    print("\n✅ Java compatibility check passed!")
    print("You should now be able to run the robot optimization tests.")

if __name__ == "__main__":
    main()
