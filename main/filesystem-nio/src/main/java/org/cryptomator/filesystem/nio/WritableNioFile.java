package org.cryptomator.filesystem.nio;

import static java.lang.String.format;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.cryptomator.filesystem.nio.OpenMode.WRITE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.cryptomator.filesystem.WritableFile;

class WritableNioFile implements WritableFile {

	private final NioFile nioFile;

	private boolean channelOpened = false;
	private boolean open = true;
	private long position = 0;

	public WritableNioFile(NioFile nioFile) {
		this.nioFile = nioFile;
	}

	@Override
	public int write(ByteBuffer source) throws UncheckedIOException {
		assertOpen();
		ensureChannelIsOpened();
		int written = nioFile.channel().writeFully(position, source);
		position += written;
		return written;
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public void position(long position) throws UncheckedIOException {
		assertOpen();
		this.position = position;
	}

	private boolean belongsToSameFilesystem(WritableFile other) {
		return other instanceof WritableNioFile && ((WritableNioFile) other).nioFile().belongsToSameFilesystem(nioFile);
	}

	@Override
	public void moveTo(WritableFile other) throws UncheckedIOException {
		assertOpen();
		if (other == this) {
			return;
		} else if (belongsToSameFilesystem(other)) {
			internalMoveTo((WritableNioFile) other);
		} else {
			throw new IllegalArgumentException("Can only move to a WritableFile from the same FileSystem");
		}
	}

	private void internalMoveTo(WritableNioFile other) {
		other.assertOpen();
		try {
			assertMovePreconditionsAreMet(other);
			closeChannelIfOpened();
			other.closeChannelIfOpened();
			Files.move(path(), other.path(), REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			open = false;
			other.open = false;
			other.nioFile.lock().writeLock().unlock();
			nioFile.lock().writeLock().unlock();
		}
	}

	private void assertMovePreconditionsAreMet(WritableNioFile other) {
		if (Files.isDirectory(path())) {
			throw new UncheckedIOException(new IOException(format("Can not move %s to %s. Source is a directory", path(), other.path())));
		}
		if (Files.isDirectory(other.path())) {
			throw new UncheckedIOException(new IOException(format("Can not move %s to %s. Target is a directory", path(), other.path())));
		}
	}

	@Override
	public void setLastModified(Instant instant) throws UncheckedIOException {
		assertOpen();
		ensureChannelIsOpened();
		try {
			Files.setLastModifiedTime(path(), FileTime.from(instant));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void delete() throws UncheckedIOException {
		assertOpen();
		try {
			closeChannelIfOpened();
			Files.delete(nioFile.path());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			open = false;
			nioFile.lock().writeLock().unlock();
		}
	}

	@Override
	public void truncate() throws UncheckedIOException {
		assertOpen();
		ensureChannelIsOpened();
		nioFile.channel().truncate(0);
	}

	@Override
	public void close() throws UncheckedIOException {
		if (!open) {
			return;
		}
		open = false;
		try {
			closeChannelIfOpened();
		} finally {
			nioFile.lock().writeLock().unlock();
		}
	}

	void ensureChannelIsOpened() {
		if (!channelOpened) {
			nioFile.channel().open(WRITE);
			channelOpened = true;
		}
	}

	private void closeChannelIfOpened() {
		if (channelOpened) {
			channel().close();
		}
	}

	SharedFileChannel channel() {
		return nioFile.channel();
	}

	Path path() {
		return nioFile.path;
	}

	NioFile nioFile() {
		return nioFile;
	}

	void assertOpen() {
		if (!open) {
			throw new UncheckedIOException(format("%s already closed.", this), new ClosedChannelException());
		}
	}

	@Override
	public String toString() {
		return format("Writable%s", this.nioFile);
	}

}