/**
 * Copyright (c) 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package de.dim.skailify.testing.filerepository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The on-disk side of one file repository: {@code <directory>/<EClassName>/<id>.xmi}, one
 * file per root object. Writes are atomic (temp file + move); readers and writers of the same
 * store are serialized by a read/write lock.
 * <p>
 * Upstream gap: this is the storage part of the proposed {@code repository.file} flavour.
 */
public final class FileStore {

	/** File extension of the object files. */
	public static final String EXTENSION = ".xmi";

	/** Directory of the saved-query catalog; dot-directories are never type collections. */
	public static final String QUERY_CATALOG = ".queries";

	private final String id;
	private final Path directory;
	private final ReadWriteLock lock = new ReentrantReadWriteLock();

	public FileStore(String id, Path directory) {
		this.id = requireNonNull(id, "id must not be null");
		this.directory = requireNonNull(directory, "directory must not be null").toAbsolutePath().normalize();
	}

	public String id() {
		return id;
	}

	public Path directory() {
		return directory;
	}

	public ReadWriteLock lock() {
		return lock;
	}

	/** The file of one object, whether it exists or not. */
	public Path file(String typeName, String objectId) {
		return typeDirectory(typeName).resolve(encode(objectId) + EXTENSION);
	}

	public Path typeDirectory(String typeName) {
		return directory.resolve(requireSafeSegment(typeName));
	}

	/** The ids of all stored objects of exactly this type (no subtypes). */
	public List<String> ids(String typeName) throws IOException {
		List<String> result = new ArrayList<>();
		for (Path file : files(typeName)) {
			String name = file.getFileName().toString();
			result.add(decode(name.substring(0, name.length() - EXTENSION.length())));
		}
		return result;
	}

	/** All object files of exactly this type. */
	public List<Path> files(String typeName) throws IOException {
		Path typeDirectory = typeDirectory(typeName);
		List<Path> result = new ArrayList<>();
		if (!Files.isDirectory(typeDirectory)) {
			return result;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(typeDirectory, "*" + EXTENSION)) {
			stream.forEach(result::add);
		}
		return result;
	}

	/** All object files of all types. */
	public List<Path> allFiles() throws IOException {
		List<Path> result = new ArrayList<>();
		if (!Files.isDirectory(directory)) {
			return result;
		}
		try (DirectoryStream<Path> types = Files.newDirectoryStream(directory,
				path -> Files.isDirectory(path) && !path.getFileName().toString().startsWith("."))) {
			for (Path type : types) {
				result.addAll(files(type.getFileName().toString()));
			}
		}
		return result;
	}

	public boolean exists(String typeName, String objectId) {
		return Files.isRegularFile(file(typeName, objectId));
	}

	public InputStream read(String typeName, String objectId) throws IOException {
		try {
			return Files.newInputStream(file(typeName, objectId));
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	/** Atomically replaces the file of one object with the bytes the writer produces. */
	public void write(String typeName, String objectId, IOConsumer<OutputStream> writer) throws IOException {
		Path target = file(typeName, objectId);
		Files.createDirectories(target.getParent());
		Path temp = Files.createTempFile(target.getParent(), ".tmp-", EXTENSION);
		try {
			try (OutputStream out = Files.newOutputStream(temp)) {
				writer.accept(out);
			}
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	public boolean delete(String typeName, String objectId) throws IOException {
		return Files.deleteIfExists(file(typeName, objectId));
	}

	/** Deletes several objects of one type; returns how many files existed. */
	public long delete(String typeName, Collection<String> objectIds) throws IOException {
		long count = 0;
		for (String objectId : objectIds) {
			if (delete(typeName, objectId)) {
				count++;
			}
		}
		return count;
	}

	/** Stores a named query (its XMI form) in the catalog, replacing one of the same name. */
	public void writeQuery(String name, String xmi) throws IOException {
		Path file = queryFile(name);
		Files.createDirectories(file.getParent());
		Path temp = Files.createTempFile(file.getParent(), ".tmp-", EXTENSION);
		try {
			Files.writeString(temp, xmi, UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** The XMI form of a named query, or {@code null} if the catalog has none of that name. */
	public String readQuery(String name) throws IOException {
		Path file = queryFile(name);
		return Files.isRegularFile(file) ? Files.readString(file, UTF_8) : null;
	}

	private Path queryFile(String name) {
		return directory.resolve(QUERY_CATALOG).resolve(encode(name) + EXTENSION);
	}

	static String encode(String objectId) {
		return URLEncoder.encode(objectId, UTF_8).replace("*", "%2A");
	}

	static String decode(String fileName) {
		return URLDecoder.decode(fileName, UTF_8);
	}

	private static String requireSafeSegment(String segment) {
		if (segment == null || segment.isBlank() || segment.contains("/") || segment.contains("\\")
				|| segment.startsWith(".")) {
			throw new IllegalArgumentException("Not a valid type segment: " + segment);
		}
		return segment;
	}

	/** An I/O consumer. */
	@FunctionalInterface
	public interface IOConsumer<T> {
		void accept(T value) throws IOException;
	}
}
