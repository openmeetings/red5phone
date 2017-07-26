/*
 * Copyright (C) 2005 Luca Veltri - University of Parma - Italy
 *
 * This source code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * Author(s):
 * Luca Veltri (luca.veltri@unipr.it)
 */

package local.media;

import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Map;

/**
 * MixerLine is a simple G711 mixer with N input lines (InputStream) and one
 * output line (the MixerLine itself).
 * <p/>
 * Each input line has an identifier (Object) used as key when adding or
 * removing the line.
 */
public class MixerLine extends InputStream {
	/** SplitterLine identifier. */
	Object mixer_id;

	/** The input lines (as Hashtable of Object->InputStream). */
	Map<Object, InputStream> input_lines;

	/** Creates a new MixerLine. */
	public MixerLine(Object mixer_id) {
		this.mixer_id = mixer_id;
		input_lines = new Hashtable<>();
	}

	/** Creates a new MixerLine. */
	public MixerLine(Object mixer_id, Map<Object, InputStream> input_lines) {
		this.mixer_id = mixer_id;
		this.input_lines = input_lines;
	}

	/** Adds a new line. */
	public void addLine(Object id, InputStream is) {
		input_lines.put(id, is);
	}

	/** Removes a line. */
	public void removeLine(Object id) {
		input_lines.remove(id);
	}

	/**
	 * Returns the number of bytes that can be read (or skipped over) from this
	 * input stream without blocking by the next caller of a method for this
	 * input stream.
	 */
	@Override
	public int available() throws IOException {
		int max = 0;
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			int n = e.getValue().available();
			if (n > max) {
				max = n;
			}
		}
		return max;
	}

	/**
	 * Closes this input stream and releases any system resources associated
	 * with the stream.
	 */
	@Override
	public void close() throws IOException {
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			e.getValue().close();
		}
		input_lines = null;
	}

	/** Marks the current position in this input stream. */
	@Override
	public void mark(int readlimit) {
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			e.getValue().mark(readlimit);
		}
	}

	/** Tests if this input stream supports the mark and reset methods. */
	@Override
	public boolean markSupported() {
		boolean supported = true;
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			if (!e.getValue().markSupported())
				supported = false;
		}
		return supported;
	}

	/** Reads the next byte of data from the input stream. */
	@Override
	public int read() throws IOException {
		int sum = 0;
		int count = 0;
		int err_code = 0;
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			InputStream is = e.getValue();
			if (is.available() > 0) {
				int value = is.read();
				if (value > 0) {
					count++;
					sum += G711.ulaw2linear(value);
				} else {
					err_code = value;
				}
			}
		}
		return count > 0 || err_code == 0 ? G711.linear2ulaw(sum) : err_code;
	}

	/**
	 * Reads some number of bytes from the input stream and stores them into the
	 * buffer array b.
	 */
	@Override
	public int read(byte[] b) throws IOException {
		int ret = super.read(b);
		return ret;
	}

	/**
	 * Reads up to len bytes of data from the input stream into an array of
	 * bytes.
	 */
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int ret = super.read(b, off, len);
		return ret;
	}

	/**
	 * Repositions this stream to the position at the time the mark method was
	 * last called on this input stream.
	 */
	@Override
	public void reset() throws IOException {
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			e.getValue().reset();
		}
	}

	/** Skips over and discards n bytes of data from this input stream. */
	@Override
	public long skip(long n) throws IOException {
		for (Map.Entry<Object, InputStream> e : input_lines.entrySet()) {
			e.getValue().skip(n);
		}
		return n;
	}
}
