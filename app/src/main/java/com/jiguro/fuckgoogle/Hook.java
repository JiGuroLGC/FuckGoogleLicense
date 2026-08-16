package com.jiguro.fuckgoogle;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
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

	// New API entry point
	private static final String NEW_ENTRY_METHOD = "checkLicense";
	// New API initialization method
	private static final String INIT_METHOD = "initializeLicenseCheck";
	// New API service-binding method
	private static final String NEW_BIND_METHOD = "bindToLicensingService";
	// Legacy API entry
	private static final String OLD_CONNECT_METHOD = "connectToLicensingService";

	private static int totalHookedCount = 0;
	private final Set<String> hookedPackages = new HashSet<>();
	private Activity currentActivity;

	// Shared callback for every known signature.
	private final XC_MethodHook licenseHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String caller = param.thisObject != null ? param.thisObject.getClass().getName() : "static";
			String pkg = callerPkg(param);
			logPkg(pkg, ">>> " + methodSig(param.method) + " intercepted, caller=[" + caller + "]");
			param.setResult(null);
		}

		@Override
		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			String pkg = callerPkg(param);
			logPkg(pkg, "<<< " + methodSig(param.method) + " blocked (result overridden)");
		}
	};

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
		final String packageName = lpparam.packageName;

		log("===== Start scanning: " + packageName + " =====");

		// Try direct hook; it works for non-packed apps.
		try {
			if (doHookLicenseCheck(lpparam.classLoader, packageName)) {
				logPkg(packageName, "Direct hook succeeded");
				return;
			}
		} catch (Throwable e) {
			logPkg(packageName, "Direct hook failed: " + e.getMessage());
		}

		// Direct hook failed, so the app may be packed. Hook Application.attach to
		// obtain the real ClassLoader once the packer has finished unpacking.
		logPkg(packageName, "Deferred hook via Application.attach");

		try {
			XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					if (hookedPackages.contains(packageName)) {
						return;
					}

					Context context = (Context) param.args[0];
					ClassLoader classLoader = context.getClassLoader();

					logPkg(packageName, "ClassLoader obtained: " + classLoader.getClass().getName());

					try {
						if (doHookLicenseCheck(classLoader, packageName)) {
							hookActivityOnCreate();
							logPkg(packageName, "Deferred hook succeeded");
						}
					} catch (Throwable e) {
						logPkg(packageName, "Deferred hook failed: " + e.getMessage());
					}
				}
			});
		} catch (Throwable e) {
			logPkg(packageName, "Failed to hook Application.attach: " + e.getMessage());
		}
	}

	// Try every known signature instead of returning after the first hit,
	// giving defensive deep coverage. Returns true if at least one hit.
	private boolean doHookLicenseCheck(ClassLoader classLoader, String packageName) {
		// Skip apps that don't carry the target class.
		if (!isTargetClassPresent(classLoader)) {
			logPkg(packageName, "Target class absent, skipping");
			return false;
		}

		logPkg(packageName, "Probing " + TARGET_CLASS);

		boolean hooked = false;

		// New static entry point: checkLicense(Context)
		hooked |= tryHookSignature(classLoader, packageName, NEW_ENTRY_METHOD, Context.class);

		// New initialization method: initializeLicenseCheck()
		hooked |= tryHookSignature(classLoader, packageName, INIT_METHOD);

		// New service-binding method: bindToLicensingService(boolean)
		hooked |= tryHookSignature(classLoader, packageName, NEW_BIND_METHOD, boolean.class);

		// Legacy signature: connectToLicensingService(boolean)
		hooked |= tryHookSignature(classLoader, packageName, OLD_CONNECT_METHOD, boolean.class);

		// Legacy signature: connectToLicensingService()
		hooked |= tryHookSignature(classLoader, packageName, OLD_CONNECT_METHOD);

		if (hooked) {
			hookedPackages.add(packageName);
			logPkg(packageName, "Hook result: SUCCESS (total hooked: " + totalHookedCount + ")");
		} else {
			logPkg(packageName, "Hook result: NO MATCH");
		}
		return hooked;
	}

	// Hook a single method signature; logs OK / MISS for each attempt.
	private boolean tryHookSignature(ClassLoader classLoader, String packageName, String methodName,
			Class<?>... paramTypes) {
		String sig = methodName + "(" + joinParams(paramTypes) + ")";

		try {
			if (paramTypes.length == 0) {
				XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, methodName, licenseHook);
			} else {
				Object[] args = new Object[paramTypes.length + 1];
				System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
				args[paramTypes.length] = licenseHook;
				XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader, methodName, args);
			}
			totalHookedCount++;
			logPkg(packageName, "  [OK]   " + sig);
			return true;
		} catch (NoSuchMethodError e) {
			logPkg(packageName, "  [MISS] " + sig + " (method absent)");
			return false;
		} catch (XposedHelpers.ClassNotFoundError e) {
			logPkg(packageName, "  [MISS] " + sig + " (class absent)");
			return false;
		}
	}

	// Quick class-presence probe to skip apps without the target class.
	private boolean isTargetClassPresent(ClassLoader classLoader) {
		try {
			XposedHelpers.findClass(TARGET_CLASS, classLoader);
			return true;
		} catch (XposedHelpers.ClassNotFoundError e) {
			return false;
		}
	}

	// Capture the first Activity for potential UI operations.
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
						logPkg(currentActivity.getPackageName(),
								"Activity captured: " + currentActivity.getClass().getName());
						logPkg(currentActivity.getPackageName(), "Module activated");
					}
				}
			});
		} catch (Throwable e) {
			log("Failed to hook Activity.onCreate: " + e.getMessage());
		}
	}

	// Logging helpers
	private void log(String msg) {
		XposedBridge.log(TAG + " " + msg);
	}

	private void logPkg(String pkg, String msg) {
		XposedBridge.log(TAG + " [" + pkg + "] " + msg);
	}

	// Resolve the target package from the first Context argument, if present.
	private String callerPkg(XC_MethodHook.MethodHookParam param) {
		if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
			return ((Context) param.args[0]).getPackageName();
		}
		return "?";
	}

	private String methodSig(Member member) {
		Class<?>[] params;
		if (member instanceof Method) {
			params = ((Method) member).getParameterTypes();
		} else if (member instanceof Constructor) {
			params = ((Constructor<?>) member).getParameterTypes();
		} else {
			params = new Class<?>[0];
		}
		return member.getName() + "(" + joinParams(params) + ")";
	}

	private String joinParams(Class<?>[] params) {
		if (params == null || params.length == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Class<?> p : params) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(p.getSimpleName());
		}
		return sb.toString();
	}
}

