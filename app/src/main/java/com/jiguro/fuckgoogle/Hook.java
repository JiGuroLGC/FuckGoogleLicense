package com.jiguro.fuckgoogle;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

	private static final String TAG = "[FuckGoogleLicence]";
	private static final String TARGET_CLASS = "com.pairip.licensecheck.LicenseClient";
	private static final String TARGET_METHOD = "connectToLicensingService";

	private static int totalHookedCount = 0;
	private final Set<String> hookedPackages = new HashSet<>();
	private Activity currentActivity;

	// Shared hook callback for both old and new method signatures.
	private final XC_MethodHook licenseHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String caller = param.thisObject != null ? param.thisObject.getClass().getName() : "unknown";
			XposedBridge.log(TAG + " License check intercepted from [" + caller + "]");
			param.setResult(null);
		}

		@Override
		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			XposedBridge.log(TAG + " License check blocked");
		}
	};

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
		final String packageName = lpparam.packageName;

		XposedBridge.log(TAG + " Scanning: " + packageName);

		// Try direct hook — works for non-packed apps.
		try {
			if (doHookLicenseCheck(lpparam.classLoader, packageName)) {
				return;
			}
		} catch (Throwable e) {
			XposedBridge.log(TAG + " Direct hook failed for [" + packageName + "]: " + e.getMessage());
		}

		// Direct hook failed — app may be packed. Hook Application.attach
		// to obtain the real ClassLoader after the packer has finished unpacking.
		XposedBridge.log(TAG + " Deferred hook via Application.attach: " + packageName);

		try {
			XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					if (hookedPackages.contains(packageName)) {
						return;
					}

					Context context = (Context) param.args[0];
					ClassLoader classLoader = context.getClassLoader();

					XposedBridge.log(TAG + " ClassLoader via attach for [" + packageName + "]: "
							+ classLoader.getClass().getName());

					try {
						if (doHookLicenseCheck(classLoader, packageName)) {
							hookActivityOnCreate();
						}
					} catch (Throwable e) {
						XposedBridge.log(TAG + " Deferred hook failed for [" + packageName + "]: " + e.getMessage());
					}
				}
			});
		} catch (Throwable e) {
			XposedBridge.log(TAG + " Failed to hook Application.attach: " + e.getMessage());
		}
	}

	// Try both the new signature (boolean) and the old signature (no-arg).
	// Returns true if a hook was successfully installed.
	private boolean doHookLicenseCheck(ClassLoader classLoader, String packageName) {
		XposedBridge.log(TAG + " Hooking " + TARGET_CLASS + "." + TARGET_METHOD + " in " + packageName);

		try {
			// New signature: connectToLicensingService(boolean)
			try {
				XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, TARGET_METHOD, boolean.class, licenseHook);
				totalHookedCount++;
				hookedPackages.add(packageName);
				XposedBridge.log(TAG + " Hooked [boolean] in " + packageName + " (total: " + totalHookedCount + ")");
				return true;
			} catch (NoSuchMethodError e) {
				XposedBridge.log(TAG + " [boolean] not found in " + packageName + ", trying no-arg...");
			}

			// Old signature: connectToLicensingService()
			try {
				XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, TARGET_METHOD, licenseHook);
				totalHookedCount++;
				hookedPackages.add(packageName);
				XposedBridge.log(TAG + " Hooked [no-arg] in " + packageName + " (total: " + totalHookedCount + ")");
				return true;
			} catch (NoSuchMethodError e) {
				XposedBridge.log(
						TAG + " NoSuchMethodError: both signatures missing in " + packageName + " — " + e.getMessage());
			}

		} catch (XposedHelpers.ClassNotFoundError e) {
			XposedBridge
					.log(TAG + " ClassNotFoundError: " + TARGET_CLASS + " not found in " + packageName + ", skipping.");

		} catch (Throwable e) {
			XposedBridge.log(TAG + " Error hooking " + TARGET_CLASS + " in " + packageName + ": "
					+ e.getClass().getName() + " — " + e.getMessage());
		}

		return false;
	}

	// Hook Activity.onCreate to capture an Activity reference for potential UI operations.
	private void hookActivityOnCreate() {
		if (currentActivity != null) {
			return;
		}

		try {
			XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					if (currentActivity == null) {
						currentActivity = (Activity) param.thisObject;
						XposedBridge.log(TAG + " Activity captured: " + currentActivity.getClass().getName());
						XposedBridge.log(TAG + " Module activated");
					}
				}
			});
		} catch (Throwable e) {
			XposedBridge.log(TAG + " Failed to hook Activity.onCreate: " + e.getMessage());
		}
	}
}

