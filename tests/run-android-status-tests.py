#!/usr/bin/env python3
"""Compile against real Android/Cordova/ESC-POS APIs and run USB mocks using cached dependencies."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
SDK = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", Path.home() / "Android/Sdk")))


def version_key(path):
    return tuple(int(n) for n in re.findall(r"\d+", path.name))


def artifact(group, name, version, extension):
    matches = list((CACHE / group / name / version).glob(f"*/{name}-{version}.{extension}"))
    if not matches:
        raise SystemExit(f"Missing cached dependency: {group}:{name}:{version} ({extension})")
    return matches[0]


def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)


with tempfile.TemporaryDirectory(prefix="thermal-usb-status-") as temp:
    work = Path(temp)
    platforms = sorted(SDK.glob("platforms/android-*/android.jar"), key=lambda p: version_key(p.parent))
    if not platforms:
        raise SystemExit("Set ANDROID_HOME to an Android SDK with platform 33 or newer.")
    android = platforms[-1]
    if version_key(android.parent) < (33,):
        raise SystemExit("Android platform 33 or newer is required.")

    jars = [artifact("org.json", "json", "20250517", "jar"), android]
    agent = artifact("org.mockito", "mockito-core", "5.20.0", "jar")
    jars.extend([
        agent,
        artifact("com.google.zxing", "core", "3.4.0", "jar"),
        artifact("net.bytebuddy", "byte-buddy", "1.17.7", "jar"),
        artifact("net.bytebuddy", "byte-buddy-agent", "1.17.7", "jar"),
        artifact("org.objenesis", "objenesis", "3.3", "jar"),
    ])
    aars = [
        artifact("com.github.nextqs", "ESCPOS-ThermalPrinter-Android", "3.6.0", "aar"),
        artifact("org.apache.cordova", "framework", "14.0.1", "aar"),
    ]
    # Cordova's Activity superclass needs AndroidX and Kotlin on the standalone JVM classpath.
    for group in CACHE.iterdir():
        if not (group.name.startswith("androidx.") or group.name in ("org.jetbrains", "org.jetbrains.kotlin")):
            continue
        for module in group.iterdir():
            if group.name == "org.jetbrains" and module.name != "annotations":
                continue
            if group.name == "androidx.databinding":
                continue
            if group.name == "org.jetbrains.kotlin" and not module.name.startswith("kotlin-stdlib"):
                continue
            for version in sorted(module.iterdir(), key=version_key, reverse=True):
                binaries = [p for ext in ("jar", "aar")
                            for p in version.glob(f"*/{module.name}-{version.name}.{ext}")]
                if binaries:
                    binary = binaries[0]
                    if binary.suffix == ".aar":
                        with zipfile.ZipFile(binary) as archive:
                            if "classes.jar" not in archive.namelist():
                                continue
                    (aars if binary.suffix == ".aar" else jars).append(binary)
                    break
    for index, aar in enumerate(aars):
        with zipfile.ZipFile(aar) as archive:
            if "classes.jar" in archive.namelist():
                jar = work / f"dependency-{index}.jar"
                jar.write_bytes(archive.read("classes.jar"))
                jars.append(jar)

    classes = work / "classes"
    classes.mkdir()
    classpath = os.pathsep.join(map(str, jars))
    # Compile production first, with the real SDK clock; only JVM tests use the clock shim.
    run("javac", "-proc:none", "-cp", classpath, "-d", str(classes),
        str(ROOT / "src/android/ThermalPrinterCordovaPlugin.java"))
    classpath = str(classes) + os.pathsep + classpath
    run("javac", "-proc:none", "-cp", classpath, "-d", str(classes),
        str(ROOT / "tests/stubs/android/os/SystemClock.java"), str(ROOT / "tests/UsbPrinterStatusTest.java"))
    run("java", f"-javaagent:{agent}", "-cp", classpath, "de.paystory.thermal_printer.UsbPrinterStatusTest")
