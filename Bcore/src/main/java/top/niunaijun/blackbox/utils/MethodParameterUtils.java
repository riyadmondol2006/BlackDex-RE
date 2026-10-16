package top.niunaijun.blackbox.utils;

import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;

public class MethodParameterUtils {

	/**
	 * Coerce a reflected argument to int regardless of whether the framework passed an
	 * Integer or a Long. On Android 13+ (API 33) many PackageManager/StorageManager
	 * methods changed their {@code flags} parameter from {@code int} to {@code long}
	 * (e.g. {@code PackageManager.ApplicationInfoFlags}); a hard {@code (int) args[i]}
	 * cast then throws {@link ClassCastException}. The low 32 bits hold the classic flags.
	 */
	public static int toInt(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		return 0;
	}

	/** Coerce a reflected argument to long (Integer or Long both accepted). */
	public static long toLong(Object value) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		return 0L;
	}

	public static String replaceFirstAppPkg(Object[] args) {
		if (args == null) {
			return null;
		}
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof String) {
				String value = (String) args[i];
				if (BlackBoxCore.get().isInstalled(value)) {
				    args[i] = BlackBoxCore.getHostPkg();
					return value;
				}
			}
		}
		return null;
	}

	public static void replaceLastUserId(Object[] args){
		int index = ArrayUtils.indexOfLast(args, Integer.class);
		if (index != -1) {
			int uid = (int) args[index];
			if (uid == BActivityThread.getUid()) {
				args[index] = Process.myUid();
			}
		}
	}

	public static Class<?>[] getAllInterface(Class clazz){
		HashSet<Class<?>> classes = new HashSet<>();
		getAllInterfaces(clazz,classes);
		Class<?>[] result=new Class[classes.size()];
		classes.toArray(result);
		return result;
	}


	public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
		Class<?>[] classes = clazz.getInterfaces();
		if (classes.length != 0) {
			interfaceCollection.addAll(Arrays.asList(classes));
		}
		if (clazz.getSuperclass() != Object.class) {
			getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
		}
	}
}
