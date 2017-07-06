package org.red5.codecs;

import java.util.Hashtable;
import java.util.Map;

/**
 * A class to store codecs attributes, used to configure them
 */
public class SIPCodecAttributes {
	private Map<String, String> codecEncodeAttributes;
	private Map<String, String> codecDecodeAttributes;

	public SIPCodecAttributes(String[] encodeAttributes, String[] decodeAttributes) {

		codecEncodeAttributes = new Hashtable<>();

		for (int i = 0; i < encodeAttributes.length; i++) {
			codecEncodeAttributes.put(encodeAttributes[i], "");
		}

		codecDecodeAttributes = new Hashtable<>();

		for (int i = 0; i < decodeAttributes.length; i++) {
			codecDecodeAttributes.put(decodeAttributes[i], "");
		}
	}

	public void setEncodeAttribute(String attributeName, String attributeValue) {

		codecEncodeAttributes.put(attributeName, attributeValue);
	}

	public String getEncodeAttribute(String attributeName) {

		return codecEncodeAttributes.get(attributeName);
	}

	public void setDecodeAttribute(String attributeName, String attributeValue) {

		codecDecodeAttributes.put(attributeName, attributeValue);
	}

	public String getDecodeAttribute(String attributeName) {

		return codecDecodeAttributes.get(attributeName);
	}

	public boolean hasEncodeAttribute(String attributeName) {

		return codecEncodeAttributes.containsKey(attributeName);
	}

	public boolean hasDecodeAttribute(String attributeName) {

		return codecDecodeAttributes.containsKey(attributeName);
	}

	public boolean hasEncodeAttributeValue(String attributeName) {

		boolean hasAttribute = false;

		if (codecEncodeAttributes.containsKey(attributeName)) {

			String attributeValue = codecEncodeAttributes.get(attributeName);

			if ((attributeValue != null) && (!attributeValue.isEmpty())) {

				hasAttribute = true;
			}
		}

		return hasAttribute;
	}

	public boolean hasDecodeAttributeValue(String attributeName) {

		boolean hasAttribute = false;

		if (codecDecodeAttributes.containsKey(attributeName)) {

			String attributeValue = codecEncodeAttributes.get(attributeName);

			if ((attributeValue != null) && (!attributeValue.isEmpty())) {

				hasAttribute = true;
			}
		}

		return hasAttribute;
	}

	@Override
	public String toString() {

		String toStringRet = "";

		if (codecEncodeAttributes.size() > 0) {

			toStringRet += "Encode attributes:\n";
		}

		for (Map.Entry<String, String> entry : codecEncodeAttributes.entrySet()) {
			toStringRet += "\t" + entry.getKey() + "=" + entry.getValue();
		}

		if (codecDecodeAttributes.size() > 0) {

			if (codecEncodeAttributes.size() > 0) {

				toStringRet += "\n";
			}

			toStringRet += "Decode attributes:\n";
		}

		for (Map.Entry<String, String> entry : codecDecodeAttributes.entrySet()) {
			toStringRet += "\t" + entry.getKey() + "=" + entry.getValue();
		}

		return toStringRet;
	}
}
