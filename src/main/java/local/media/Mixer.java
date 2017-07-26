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
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Map;

/**
 * MixerLine is a simple G711 mixer with M input lines (OutputStreams) and N
 * output lines (InputStreams).
 * <p/>
 * Each line has an identifier (Object) used as key when adding or removing the
 * line.
 */
public class Mixer {
	/** The splitter lines (as Hashtable of Object->SplitterLine). */
	Map<Object, SplitterLine> splitter_lines;

	/** The mixer lines (as Hashtable of Object->MixerLine). */
	Map<Object, MixerLine> mixer_lines;

	/** Creates a new Mixer. */
	public Mixer() {
		splitter_lines = new Hashtable<>();
		mixer_lines = new Hashtable<>();
	}

	/** Close the Mixer. */
	public void close() throws IOException {
		for (Map.Entry<Object, SplitterLine> e : splitter_lines.entrySet()) {
			e.getValue().close();
		}
		for (Map.Entry<Object, MixerLine> e : mixer_lines.entrySet()) {
			e.getValue().close();
		}
		mixer_lines = null;
		splitter_lines = null;
	}

	/** Adds a new input line. */
	public OutputStream newInputLine(Object id) throws IOException {
		SplitterLine sl = new SplitterLine(id);
		for (Map.Entry<Object, MixerLine> e : mixer_lines.entrySet()) {
			Object mid = e.getKey();
			if (!mid.equals(id)) {
				ExtendedPipedInputStream is = new ExtendedPipedInputStream();
				ExtendedPipedOutputStream os = new ExtendedPipedOutputStream(is);
				sl.addLine(mid, os);
				mixer_lines.get(mid).addLine(id, is);
			}
		}
		splitter_lines.put(id, sl);
		return sl;
	}

	/** Removes a input line. */
	public void removeInputLine(Object id) {
		SplitterLine sl = splitter_lines.get(id);
		splitter_lines.remove(id);
		for (Map.Entry<Object, MixerLine> e : mixer_lines.entrySet()) {
			Object mid = e.getKey();
			if (!mid.equals(id)) {
				sl.removeLine(mid);
				mixer_lines.get(mid).removeLine(id);
			}
		}
		try {
			sl.close();
		} catch (Exception e) {
		}
	}

	/** Adds a new output line. */
	public InputStream newOutputLine(Object id) throws IOException {
		MixerLine ml = new MixerLine(id);
		for (Map.Entry<Object, SplitterLine> e : splitter_lines.entrySet()) {
			Object sid = e.getKey();
			if (!sid.equals(id)) {
				ExtendedPipedInputStream is = new ExtendedPipedInputStream();
				ExtendedPipedOutputStream os = new ExtendedPipedOutputStream(is);
				ml.addLine(sid, is);
				splitter_lines.get(sid).addLine(id, os);
			}
		}
		mixer_lines.put(id, ml);
		return ml;
	}

	/** Removes a output line. */
	public void removeOutputLine(Object id) {
		MixerLine ml = mixer_lines.get(id);
		mixer_lines.remove(id);
		for (Map.Entry<Object, SplitterLine> e : splitter_lines.entrySet()) {
			Object sid = e.getKey();
			if (!sid.equals(id)) {
				ml.removeLine(sid);
				splitter_lines.get(sid).removeLine(id);
			}
		}
		try {
			ml.close();
		} catch (Exception e) {
		}
	}
}
