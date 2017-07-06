package org.openmeetings.utils;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

/**
 * IO related utility classes
 */
public class IOUtils {

	public static void close(Logger l, Closeable io) {
		try {
			io.close();
		} catch (Exception e) {
			l.error("Unexpected error while closing:" + io, e);
		}
	}

	public static String readFileToString(File file) {
		String result;
		try {
			result = FileUtils.readFileToString(file, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return result;
	}

	public static void writeStringToFile(File file, String text) {
		try {
			FileUtils.writeStringToFile(file, text, UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void touch(File file) {
		try {
			FileUtils.touch(file);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			for (File file : files) {
				if (file.isDirectory()) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		return (path.delete());
	}
}
