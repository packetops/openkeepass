package de.slackspace.openkeepass;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bouncycastle.util.encoders.Base64;

import de.slackspace.openkeepass.crypto.Decrypter;
import de.slackspace.openkeepass.crypto.ProtectedStringCrypto;
import de.slackspace.openkeepass.crypto.Salsa20;
import de.slackspace.openkeepass.crypto.Sha256;
import de.slackspace.openkeepass.domain.CompressionAlgorithm;
import de.slackspace.openkeepass.domain.CrsAlgorithm;
import de.slackspace.openkeepass.domain.KeePassFile;
import de.slackspace.openkeepass.domain.KeePassHeader;
import de.slackspace.openkeepass.domain.KeyFile;
import de.slackspace.openkeepass.exception.KeePassDatabaseUnreadable;
import de.slackspace.openkeepass.exception.KeePassDatabaseUnwriteable;
import de.slackspace.openkeepass.stream.HashedBlockInputStream;
import de.slackspace.openkeepass.stream.HashedBlockOutputStream;
import de.slackspace.openkeepass.stream.SafeInputStream;
import de.slackspace.openkeepass.util.ByteUtils;
import de.slackspace.openkeepass.util.StreamUtils;
import de.slackspace.openkeepass.xml.KeePassDatabaseXmlParser;
import de.slackspace.openkeepass.xml.KeyFileXmlParser;

/**
 * A KeePassDatabase is the central API class to read and write a KeePass
 * database file.
 * <p>
 * Currently the following KeePass files are supported:
 *
 * <ul>
 * <li>KeePass Database V2 with password</li>
 * <li>KeePass Database V2 with keyfile</li>
 * <li>KeePass Database V2 with combined password and keyfile</li>
 * </ul>
 *
 * A typical read use-case should use the following idiom:
 *
 * <pre>
 * // open database
 * KeePassFile database = KeePassDatabase.getInstance("keePassDatabasePath").openDatabase("secret");
 *
 * // get password entries
 * List&lt;Entry&gt; entries = database.getEntries();
 * ...
 * </pre>
 *
 * If the database could not be opened an exception of type <tt>KeePassDatabaseUnreadable</tt> will be
 * thrown.
 * <p>
 * A typical write use-case should use the following idiom:
 *
 * <pre>
 * // build an entry
 * Entry entryOne = new EntryBuilder("First entry").username("Carl").password("secret").build();
 *
 * // build more entries or groups as you like
 * ...
 *
 * // build KeePass model
 * KeePassFile keePassFile = new KeePassFileBuilder("testDB").addTopEntries(entryOne).build();
 *
 * // write KeePass database file
 * KeePassDatabase.write(keePassFile, "secret", new FileOutputStream("keePassDatabasePath"));
 * </pre>
 *
 * @see KeePassFile
 *
 */
public class KeePassDatabase {

	private static final String UTF_8 = "UTF-8";
	private static final String MSG_UTF8_NOT_SUPPORTED = "The encoding UTF-8 is not supported";
	private static final String MSG_EMPTY_MASTER_KEY = "The password for the database must not be null. Please provide a valid password.";
	
	private KeePassHeader keepassHeader = new KeePassHeader();
	private byte[] keepassFile;

	protected Decrypter decrypter = new Decrypter();
	protected KeePassDatabaseXmlParser keePassDatabaseXmlParser = new KeePassDatabaseXmlParser();
	protected KeyFileXmlParser keyFileXmlParser = new KeyFileXmlParser();

	private KeePassDatabase(InputStream inputStream) {
		try {
			keepassFile = StreamUtils.toByteArray(inputStream);
			keepassHeader.checkVersionSupport(keepassFile);
			keepassHeader.read(keepassFile);
		} catch (IOException e) {
			throw new KeePassDatabaseUnreadable("Could not open database file", e);
		}
	}

	/**
	 * Retrieves a KeePassDatabase instance. The instance returned is based on
	 * the given database filename and tries to parse the database header of it.
	 *
	 * @param keePassDatabaseFile
	 *            a KeePass database filename, must not be NULL
	 * @return a KeePassDatabase
	 */
	public static KeePassDatabase getInstance(String keePassDatabaseFile) {
		return getInstance(new File(keePassDatabaseFile));
	}

	/**
	 * Retrieves a KeePassDatabase instance. The instance returned is based on
	 * the given database file and tries to parse the database header of it.
	 *
	 * @param keePassDatabaseFile
	 *            a KeePass database file, must not be NULL
	 * @return a KeePassDatabase
	 */
	public static KeePassDatabase getInstance(File keePassDatabaseFile) {
		if (keePassDatabaseFile == null) {
			throw new IllegalArgumentException("You must provide a valid KeePass database file.");
		}

		InputStream keePassDatabaseStream = null;
		try {
			keePassDatabaseStream = new FileInputStream(keePassDatabaseFile);
			return getInstance(keePassDatabaseStream);
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException(
					"The KeePass database file could not be found. You must provide a valid KeePass database file.", e);
		} finally {
			if (keePassDatabaseStream != null) {
				try {
					keePassDatabaseStream.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}

	/**
	 * Retrieves a KeePassDatabase instance. The instance returned is based on
	 * the given input stream and tries to parse the database header of it.
	 *
	 * @param keePassDatabaseStream
	 *            an input stream of a KeePass database, must not be NULL
	 * @return a KeePassDatabase
	 */
	public static KeePassDatabase getInstance(InputStream keePassDatabaseStream) {
		if (keePassDatabaseStream == null) {
			throw new IllegalArgumentException("You must provide a non-empty KeePass database stream.");
		}

		return new KeePassDatabase(keePassDatabaseStream);
	}

	/**
	 * Opens a KeePass database with the given password and returns the
	 * KeePassFile for further processing.
	 * <p>
	 * If the database cannot be decrypted with the provided password an
	 * exception will be thrown.
	 *
	 * @param password
	 *            the password to open the database
	 * @return a KeePassFile
	 * @see KeePassFile
	 */
	public KeePassFile openDatabase(String password) {
		if (password == null) {
			throw new IllegalArgumentException(MSG_EMPTY_MASTER_KEY);
		}

		try {
			byte[] passwordBytes = password.getBytes(UTF_8);
			byte[] hashedPassword = Sha256.hash(passwordBytes);

			return decryptAndParseDatabase(hashedPassword);
		} catch (UnsupportedEncodingException e) {
			throw new UnsupportedOperationException(MSG_UTF8_NOT_SUPPORTED, e);
		}
	}

	/**
	 * Opens a KeePass database with the given password and keyfile and returns
	 * the KeePassFile for further processing.
	 * <p>
	 * If the database cannot be decrypted with the provided password and
	 * keyfile an exception will be thrown.
	 *
	 * @param password
	 *            the password to open the database
	 * @param keyFile
	 *            the password to open the database
	 * @return a KeePassFile
	 * @see KeePassFile
	 */
	public KeePassFile openDatabase(String password, File keyFile) {
		if (password == null) {
			throw new IllegalArgumentException(MSG_EMPTY_MASTER_KEY);
		}
		if (keyFile == null) {
			throw new IllegalArgumentException("You must provide a valid KeePass keyfile.");
		}

		try {
			return openDatabase(password, new FileInputStream(keyFile));
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException(
					"The KeePass keyfile could not be found. You must provide a valid KeePass keyfile.", e);
		}
	}

	/**
	 * Opens a KeePass database with the given password and keyfile stream and
	 * returns the KeePassFile for further processing.
	 * <p>
	 * If the database cannot be decrypted with the provided password and
	 * keyfile stream an exception will be thrown.
	 *
	 * @param password
	 *            the password to open the database
	 * @param keyFileStream
	 *            the keyfile to open the database as stream
	 * @return a KeePassFile
	 * @see KeePassFile
	 */
	public KeePassFile openDatabase(String password, InputStream keyFileStream) {
		if (password == null) {
			throw new IllegalArgumentException(MSG_EMPTY_MASTER_KEY);
		}
		if (keyFileStream == null) {
			throw new IllegalArgumentException("You must provide a non-empty KeePass keyfile stream.");
		}

		try {
			byte[] passwordBytes = password.getBytes(UTF_8);
			byte[] hashedPassword = Sha256.hash(passwordBytes);

			KeyFile keyFile = keyFileXmlParser.fromXml(keyFileStream);
			byte[] protectedBuffer = Base64.decode(keyFile.getKey().getData().getBytes(UTF_8));
			if (protectedBuffer.length != 32) {
				protectedBuffer = Sha256.hash(protectedBuffer);
			}

			return decryptAndParseDatabase(ByteUtils.concat(hashedPassword, protectedBuffer));
		} catch (UnsupportedEncodingException e) {
			throw new UnsupportedOperationException(MSG_UTF8_NOT_SUPPORTED, e);
		}
	}

	/**
	 * Opens a KeePass database with the given keyfile and returns the
	 * KeePassFile for further processing.
	 * <p>
	 * If the database cannot be decrypted with the provided password an
	 * exception will be thrown.
	 *
	 * @param keyFile
	 *            the password to open the database
	 * @return a KeePassFile the keyfile to open the database
	 * @see KeePassFile
	 */
	public KeePassFile openDatabase(File keyFile) {
		if (keyFile == null) {
			throw new IllegalArgumentException("You must provide a valid KeePass keyfile.");
		}

		try {
			return openDatabase(new FileInputStream(keyFile));
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException(
					"The KeePass keyfile could not be found. You must provide a valid KeePass keyfile.", e);
		}
	}

	/**
	 * Opens a KeePass database with the given keyfile stream and returns the
	 * KeePassFile for further processing.
	 * <p>
	 * If the database cannot be decrypted with the provided keyfile an
	 * exception will be thrown.
	 *
	 * @param keyFileStream
	 *            the keyfile to open the database as stream
	 * @return a KeePassFile
	 * @see KeePassFile
	 */
	public KeePassFile openDatabase(InputStream keyFileStream) {
		if (keyFileStream == null) {
			throw new IllegalArgumentException("You must provide a non-empty KeePass keyfile stream.");
		}

		try {
			KeyFile keyFile = keyFileXmlParser.fromXml(keyFileStream);
			byte[] protectedBuffer = Base64.decode(keyFile.getKey().getData().getBytes(UTF_8));

			return decryptAndParseDatabase(protectedBuffer);
		} catch (UnsupportedEncodingException e) {
			throw new UnsupportedOperationException(MSG_UTF8_NOT_SUPPORTED, e);
		}
	}

	@SuppressWarnings("resource")
	private KeePassFile decryptAndParseDatabase(byte[] key) {
		try {
			byte[] aesDecryptedDbFile = decrypter.decryptDatabase(key, keepassHeader, keepassFile);

			byte[] startBytes = new byte[32];
			SafeInputStream decryptedStream = new SafeInputStream(new ByteArrayInputStream(aesDecryptedDbFile));

			// Metadata must be skipped here
			decryptedStream.skipSafe(KeePassHeader.VERSION_SIGNATURE_LENGTH + keepassHeader.getHeaderSize());
			decryptedStream.readSafe(startBytes);

			// Compare startBytes
			if (!Arrays.equals(keepassHeader.getStreamStartBytes(), startBytes)) {
				throw new KeePassDatabaseUnreadable(
						"The keepass database file seems to be corrupt or cannot be decrypted.");
			}

			HashedBlockInputStream hashedBlockInputStream = new HashedBlockInputStream(decryptedStream);
			byte[] hashedBlockBytes = StreamUtils.toByteArray(hashedBlockInputStream);

			byte[] decompressed = hashedBlockBytes;

			// Unzip if necessary
			if (keepassHeader.getCompression().equals(CompressionAlgorithm.Gzip)) {
				GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(hashedBlockBytes));
				decompressed = StreamUtils.toByteArray(gzipInputStream);
			}

			ProtectedStringCrypto protectedStringCrypto;
			if (keepassHeader.getCrsAlgorithm().equals(CrsAlgorithm.Salsa20)) {
				protectedStringCrypto = Salsa20.createInstance(keepassHeader.getProtectedStreamKey());
			} else {
				throw new UnsupportedOperationException("Only Salsa20 is supported as CrsAlgorithm at the moment!");
			}

			return keePassDatabaseXmlParser.fromXml(new ByteArrayInputStream(decompressed), protectedStringCrypto);
		} catch (IOException e) {
			throw new KeePassDatabaseUnreadable("Could not open database file", e);
		}
	}

	/**
	 * Gets the KeePassDatabase header.
	 *
	 * @return the database header
	 */
	public KeePassHeader getHeader() {
		return keepassHeader;
	}

	/**
	 * Encrypts a {@link KeePassFile} with the given password and writes it to
	 * the given file location.
	 * <p>
	 * If the KeePassFile cannot be encrypted an exception will be thrown.
	 *
	 * @param keePassFile
	 *            the keePass model which should be written
	 * @param password
	 *            the password to encrypt the database
	 * @param keePassDatabaseFile
	 *            the target location where the database file will be written
	 * @see KeePassFile
	 */
	public static void write(KeePassFile keePassFile, String password, String keePassDatabaseFile) {
		if (keePassDatabaseFile == null || keePassDatabaseFile.isEmpty()) {
			throw new IllegalArgumentException(
					"You must provide a non-empty path where the database should be written to.");
		}

		try {
			write(keePassFile, password, new FileOutputStream(keePassDatabaseFile));
		} catch (FileNotFoundException e) {
			throw new KeePassDatabaseUnreadable("Could not find database file", e);
		}
	}

	/**
	 * Encrypts a {@link KeePassFile} with the given password and writes it to
	 * the given stream.
	 * <p>
	 * If the KeePassFile cannot be encrypted an exception will be thrown.
	 *
	 * @param keePassFile
	 *            the keePass model which should be written
	 * @param password
	 *            the password to encrypt the database
	 * @param stream
	 *            the target stream where the output will be written
	 * @see KeePassFile
	 *
	 */
	public static void write(KeePassFile keePassFile, String password, OutputStream stream) {
		if (stream == null) {
			throw new IllegalArgumentException("You must provide a stream to write to.");
		}

		try {
			if (!validateKeePassFile(keePassFile)) {
				throw new KeePassDatabaseUnwriteable(
						"The provided keePassFile is not valid. A valid keePassFile must contain of meta and root group and the root group must at least contain one group.");
			}

			KeePassHeader header = new KeePassHeader();
			header.initialize();

			byte[] passwordBytes = password.getBytes(UTF_8);
			byte[] hashedPassword = Sha256.hash(passwordBytes);

			// Marshall xml
			ProtectedStringCrypto protectedStringCrypto = Salsa20.createInstance(header.getProtectedStreamKey());
			byte[] keePassFilePayload = new KeePassDatabaseXmlParser().toXml(keePassFile, protectedStringCrypto)
					.toByteArray();

			// Unzip
			ByteArrayOutputStream streamToUnzip = new ByteArrayOutputStream();
			GZIPOutputStream gzipOutputStream = new GZIPOutputStream(streamToUnzip);
			gzipOutputStream.write(keePassFilePayload);
			gzipOutputStream.close();

			// Unhash
			ByteArrayOutputStream streamToUnhashBlock = new ByteArrayOutputStream();
			HashedBlockOutputStream hashBlockOutputStream = new HashedBlockOutputStream(streamToUnhashBlock);
			hashBlockOutputStream.write(streamToUnzip.toByteArray());
			hashBlockOutputStream.close();

			// Write Header
			ByteArrayOutputStream streamToEncrypt = new ByteArrayOutputStream();
			streamToEncrypt.write(header.getBytes());
			streamToEncrypt.write(header.getStreamStartBytes());

			// Write Content
			streamToEncrypt.write(streamToUnhashBlock.toByteArray());

			// Encrypt
			byte[] encryptedDatabase = new Decrypter().encryptDatabase(hashedPassword, header,
					streamToEncrypt.toByteArray());

			// Write database to stream
			stream.write(encryptedDatabase);
		} catch (IOException e) {
			throw new KeePassDatabaseUnwriteable("Could not write database file", e);
		} finally {
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}

	private static boolean validateKeePassFile(KeePassFile keePassFile) {
		if (keePassFile == null || keePassFile.getMeta() == null) {
			return false;
		}

		if (keePassFile.getRoot() == null || keePassFile.getRoot().getGroups().isEmpty()) {
			return false;
		}

		return true;
	}

}
