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
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Map;

/**
 * SplitterLine is a simple splitter with one input line (the SplitterLine
 * itself) and N output lines (OutputStreams).
 * <p/>
 * Each output line has an identifier (Object) used as key when adding or
 * removing the line.
 */
public class SplitterLine extends OutputStream {
	/** SplitterLine identifier. */
	Object splitter_id;

	/** The output lines (as Hashtable of Object->OutputStream). */
	Map<Object, OutputStream> output_lines;

	/** Creates a new SplitterLine. */
	public SplitterLine(Object splitter_id) {
		this.splitter_id = splitter_id;
		output_lines = new Hashtable<>();
	}

	/** Creates a new SplitterLine. */
	public SplitterLine(Object splitter_id, Map<Object, OutputStream> output_lines) {
		this.splitter_id = splitter_id;
		this.output_lines = output_lines;
	}

	/** Adds a new line. */
	public void addLine(Object id, OutputStream os) {
		output_lines.put(id, os);
	}

	/** Removes a line. */
	public void removeLine(Object id) {
		output_lines.remove(id);
	}

	/**
	 * Closes this output stream and releases any system resources associated
	 * with this stream.
	 */
	@Override
	public void close() throws IOException {
		for (Map.Entry<Object, OutputStream> e : output_lines.entrySet()) {
			e.getValue().close();
		}
		output_lines = null;
	}

	/**
	 * Flushes this output stream and forces any buffered output bytes to be
	 * written out.
	 */
	@Override
	public void flush() throws IOException {
		for (Map.Entry<Object, OutputStream> e : output_lines.entrySet()) {
			e.getValue().flush();
		}
	}

	/**
	 * Writes b.length bytes from the specified byte array to this output
	 * stream.
	 */
	@Override
	public void write(byte[] b) throws IOException {
		super.write(b);
	}

	/**
	 * Writes len bytes from the specified byte array starting at offset off to
	 * this output stream.
	 */
	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		super.write(b, off, len);
	}

	/** Writes the specified byte to this output stream. */
	@Override
	public void write(int b) throws IOException {
		for (Map.Entry<Object, OutputStream> e : output_lines.entrySet()) {
			try {
				e.getValue().write(b);
			} catch (IOException ex) {
				System.err.println("SL(" + splitter_id + "): ERROR while writing on line " + e.getKey());
				ex.printStackTrace();
				throw new IOException("SplitterLine error");
			}
		}
	}

}
