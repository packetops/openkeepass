package de.slackspace.openkeepass.xml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXB;

import de.slackspace.openkeepass.crypto.ProtectedStringCrypto;
import de.slackspace.openkeepass.domain.Entry;
import de.slackspace.openkeepass.domain.History;
import de.slackspace.openkeepass.domain.KeePassFile;
import de.slackspace.openkeepass.domain.Property;
import de.slackspace.openkeepass.domain.PropertyValue;
import de.slackspace.openkeepass.domain.enricher.IconEnricher;

public class KeePassDatabaseXmlParser {

	public KeePassFile fromXml(InputStream inputStream, ProtectedStringCrypto protectedStringCrypto) {
		KeePassFile keePassFile = JAXB.unmarshal(inputStream, KeePassFile.class);

		processAllProtectedValues(false, protectedStringCrypto, keePassFile);

		keePassFile = new IconEnricher().enrichNodesWithIconData(keePassFile);

		return keePassFile;
	}

	public ByteArrayOutputStream toXml(KeePassFile keePassFile, ProtectedStringCrypto protectedStringCrypto) {
		processAllProtectedValues(true, protectedStringCrypto, keePassFile);

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		JAXB.marshal(keePassFile, outputStream);

		return outputStream;
	}

	private void processAllProtectedValues(boolean encrypt, ProtectedStringCrypto protectedStringCrypto,
			KeePassFile keePassFile) {
		// Decrypt/Encrypt all protected values
		List<Entry> entries = keePassFile.getEntries();
		for (Entry entry : entries) {
			processProtectedValues(encrypt, entry, protectedStringCrypto);

			// Also process historic password values
			History history = entry.getHistory();
			if (history != null) {
				for (Entry historicEntry : history.getHistoricEntries()) {
					processProtectedValues(encrypt, historicEntry, protectedStringCrypto);
				}
			}
		}
	}

	private void processProtectedValues(boolean encrypt, Entry entry, ProtectedStringCrypto protectedStringCrypto) {
		List<Property> removeList = new ArrayList<Property>();
		List<Property> addList = new ArrayList<Property>();

		List<Property> properties = entry.getProperties();
		for (Property property : properties) {
			PropertyValue propertyValue = property.getPropertyValue();

			if (propertyValue.getValue() != null && !propertyValue.getValue().isEmpty()
					&& propertyValue.isProtected()) {

				String processedValue;
				if (encrypt) {
					processedValue = protectedStringCrypto.encrypt(propertyValue.getValue());
				} else {
					processedValue = protectedStringCrypto.decrypt(propertyValue.getValue());
				}

				removeList.add(property);
				addList.add(new Property(property.getKey(), processedValue, property.isProtected()));
			}
		}

		properties.removeAll(removeList);
		properties.addAll(addList);
	}
}
