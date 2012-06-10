package ar.com.hjg.pngj;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import ar.com.hjg.pngj.chunks.ChunkCopyBehaviour;
import ar.com.hjg.pngj.chunks.ChunkHelper;
import ar.com.hjg.pngj.chunks.ChunksList;
import ar.com.hjg.pngj.chunks.ChunksListForWrite;
import ar.com.hjg.pngj.chunks.PngChunk;
import ar.com.hjg.pngj.chunks.PngChunkIEND;
import ar.com.hjg.pngj.chunks.PngChunkIHDR;
import ar.com.hjg.pngj.chunks.PngChunkTextVar;
import ar.com.hjg.pngj.chunks.PngMetadata;

/**
 * Writes a PNG image, line by line.
 */
public class PngWriter {

	public final ImageInfo imgInfo;

	private final String filename; // optional, can be a description

	/**
	 * last read row number, starting from 0
	 */
	protected int rowNum = -1;

	private final ChunksListForWrite chunkList;

	private final PngMetadata metadata; // high level wrapper over chunkList

	/**
	 * Current chunk grounp, (0-6) already read or reading
	 * <p>
	 * see {@link ChunksList}
	 */
	protected int currentChunkGroup = -1;

	/**
	 * PNG filter strategy
	 */
	protected FilterWriteStrategy filterStrat;

	/**
	 * zip compression level 0 - 9
	 */
	private int compLevel = 6;
	private boolean shouldCloseStream = true; // true: closes stream after ending write

	private PngIDatChunkOutputStream datStream;

	private DeflaterOutputStream datStreamDeflated;

	/**
	 * Deflate algortithm compression strategy
	 */
	private int deflaterStrategy = Deflater.FILTERED;

	private int[] histox = new int[256]; // auxiliar buffer, only used by reportResultsForFilter

	private int idatMaxSize = 0; // 0=use default (PngIDatChunkOutputStream 32768)
	private final OutputStream os;
	// current line, one (packed) sample per element (layout differnt from rowb!)
	protected int[] scanline = null;

	protected byte[] rowb = null; // element 0 is filter type!
	protected byte[] rowbfilter = null; // current line with filter

	protected byte[] rowbprev = null; // rowb prev

	public PngWriter(OutputStream outputStream, ImageInfo imgInfo) {
		this(outputStream, imgInfo, "[NO FILENAME AVAILABLE]");
	}

	/**
	 * Constructs a new PngWriter from a output stream. After construction nothing is writen yet. You still can set some
	 * parameters (compression, filters) and queue chunks before start writing the pixels.
	 * <p>
	 * See also <code>FileHelper.createPngWriter()</code> if available.
	 * 
	 * @param outputStream
	 *            Opened stream for binary writing
	 * @param imgInfo
	 *            Basic image parameters
	 * @param filenameOrDescription
	 *            Optional, just for error/debug messages
	 */
	public PngWriter(OutputStream outputStream, ImageInfo imgInfo, String filenameOrDescription) {
		this.filename = filenameOrDescription == null ? "" : filenameOrDescription;
		this.os = outputStream;
		this.imgInfo = imgInfo;
		// prealloc
		scanline = new int[imgInfo.samplesPerRowP];
		rowb = new byte[imgInfo.bytesPerRow + 1];
		rowbprev = new byte[rowb.length];
		rowbfilter = new byte[rowb.length];
		chunkList = new ChunksListForWrite(imgInfo);
		metadata = new PngMetadata(chunkList);
		filterStrat = new FilterWriteStrategy(imgInfo, FilterType.FILTER_DEFAULT); // can be changed
	}

	private void init() {
		datStream = new PngIDatChunkOutputStream(this.os, idatMaxSize);
		writeSignatureAndIHDR();
		writeFirstChunks();
	}

	private void reportResultsForFilter(int rown, FilterType type, boolean tentative) {
		Arrays.fill(histox, 0);
		int s = 0, v;
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			v = rowbfilter[i];
			if (v < 0)
				s -= (int) v;
			else
				s += (int) v;
			histox[v & 0xFF]++;
		}
		filterStrat.fillResultsForFilter(rown, type, s, histox, tentative);
	}

	private void writeEndChunk() {
		PngChunkIEND c = new PngChunkIEND(imgInfo);
		c.createRawChunk().writeChunk(os);
	}

	private void writeFirstChunks() {
		int nw = 0;
		currentChunkGroup = ChunksList.CHUNK_GROUP_1_AFTERIDHR;
		nw = chunkList.writeChunks(os, currentChunkGroup);
		currentChunkGroup = ChunksList.CHUNK_GROUP_2_PLTE;
		nw = chunkList.writeChunks(os, currentChunkGroup);
		if (nw > 0 && imgInfo.greyscale)
			throw new PngjOutputException("cannot write palette for this format");
		if (nw == 0 && imgInfo.indexed)
			throw new PngjOutputException("missing palette");
		currentChunkGroup = ChunksList.CHUNK_GROUP_3_AFTERPLTE;
		nw = chunkList.writeChunks(os, currentChunkGroup);
		currentChunkGroup = ChunksList.CHUNK_GROUP_4_IDAT;
	}

	private void writeLastChunks() { // not including end
		currentChunkGroup = ChunksList.CHUNK_GROUP_5_AFTERIDAT;
		chunkList.writeChunks(os, currentChunkGroup);
		// should not be unwriten chunks
		List<PngChunk> pending = chunkList.getQueuedChunks();
		if (!pending.isEmpty())
			throw new PngjOutputException(pending.size() + " chunks were not written! Eg: " + pending.get(0).toString());
		currentChunkGroup = ChunksList.CHUNK_GROUP_6_END;
	}

	/**
	 * Write id signature and also "IHDR" chunk
	 */
	private void writeSignatureAndIHDR() {
		currentChunkGroup = ChunksList.CHUNK_GROUP_0_IDHR;
		if (datStreamDeflated == null) {
			Deflater def = new Deflater(compLevel);
			def.setStrategy(deflaterStrategy);
			datStreamDeflated = new DeflaterOutputStream(datStream, def, 8192);
		}
		PngHelperInternal.writeBytes(os, PngHelperInternal.pngIdBytes); // signature
		PngChunkIHDR ihdr = new PngChunkIHDR(imgInfo);
		// http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html
		ihdr.setCols(imgInfo.cols);
		ihdr.setRows(imgInfo.rows);
		ihdr.setBitspc(imgInfo.bitDepth);
		int colormodel = 0;
		if (imgInfo.alpha)
			colormodel += 0x04;
		if (imgInfo.indexed)
			colormodel += 0x01;
		if (!imgInfo.greyscale)
			colormodel += 0x02;
		ihdr.setColormodel(colormodel);
		ihdr.setCompmeth(0); // compression method 0=deflate
		ihdr.setFilmeth(0); // filter method (0)
		ihdr.setInterlaced(0); // we never interlace
		ihdr.createRawChunk().writeChunk(os);

	}

	protected void convertRowToBytes() {
		// http://www.libpng.org/pub/png/spec/1.2/PNG-DataRep.html
		int j = 1;
		if (imgInfo.bitDepth <= 8) {
			for (int x : scanline) { // optimized
				rowb[j++] = (byte) x;
			}
		} else { // 16 bitspc
			for (int x : scanline) { // optimized
				rowb[j++] = (byte) (x >> 8);
				rowb[j++] = (byte) (x);
			}
		}
	}

	private void filterRow(int rown) {
		// warning: filters operation rely on: "previos row" (rowbprev) is
		// initialized to 0 the first time
		if (filterStrat.shouldTestAll(rown)) {
			filterRowNone();
			reportResultsForFilter(rown, FilterType.FILTER_NONE, true);
			filterRowSub();
			reportResultsForFilter(rown, FilterType.FILTER_SUB, true);
			filterRowUp();
			reportResultsForFilter(rown, FilterType.FILTER_UP, true);
			filterRowAverage();
			reportResultsForFilter(rown, FilterType.FILTER_AVERAGE, true);
			filterRowPaeth();
			reportResultsForFilter(rown, FilterType.FILTER_PAETH, true);
		}
		FilterType filterType = filterStrat.gimmeFilterType(rown, true);
		rowbfilter[0] = (byte) filterType.val;
		switch (filterType) {
		case FILTER_NONE:
			filterRowNone();
			break;
		case FILTER_SUB:
			filterRowSub();
			break;
		case FILTER_UP:
			filterRowUp();
			break;
		case FILTER_AVERAGE:
			filterRowAverage();
			break;
		case FILTER_PAETH:
			filterRowPaeth();
			break;
		default:
			throw new PngjOutputException("Filter type " + filterType + " not implemented");
		}
		reportResultsForFilter(rown, filterType, false);
	}

	protected void filterRowAverage() {
		int i, j, imax;
		imax = imgInfo.bytesPerRow;
		for (j = 1 - imgInfo.bytesPixel, i = 1; i <= imax; i++, j++) {
			rowbfilter[i] = (byte) (rowb[i] - ((rowbprev[i] & 0xFF) + (j > 0 ? (rowb[j] & 0xFF) : 0)) / 2);
		}
	}

	protected void filterRowNone() {
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			rowbfilter[i] = (byte) rowb[i];
		}
	}

	protected void filterRowPaeth() {
		int i, j, imax;
		imax = imgInfo.bytesPerRow;
		for (j = 1 - imgInfo.bytesPixel, i = 1; i <= imax; i++, j++) {
			rowbfilter[i] = (byte) (rowb[i] - PngHelperInternal.filterPaethPredictor(j > 0 ? (rowb[j] & 0xFF) : 0,
					rowbprev[i] & 0xFF, j > 0 ? (rowbprev[j] & 0xFF) : 0));
		}
	}

	protected void filterRowSub() {
		int i, j;
		for (i = 1; i <= imgInfo.bytesPixel; i++)
			rowbfilter[i] = (byte) rowb[i];
		for (j = 1, i = imgInfo.bytesPixel + 1; i <= imgInfo.bytesPerRow; i++, j++) {
			rowbfilter[i] = (byte) (rowb[i] - rowb[j]);
		}
	}

	protected void filterRowUp() {
		for (int i = 1; i <= imgInfo.bytesPerRow; i++) {
			rowbfilter[i] = (byte) (rowb[i] - rowbprev[i]);
		}
	}

	protected int sumRowbfilter() { // sums absolute value
		int s = 0;
		for (int i = 1; i <= imgInfo.bytesPerRow; i++)
			if (rowbfilter[i] < 0)
				s -= (int) rowbfilter[i];
			else
				s += (int) rowbfilter[i];
		return s;
	}

	/**
	 * copy chunks from reader - copy_mask : see ChunksToWrite.COPY_XXX
	 * <p>
	 * If we are after idat, only considers those chunks after IDAT in PngReader
	 * <p>
	 * TODO: this should be more customizable
	 */
	private void copyChunks(PngReader reader, int copy_mask, boolean onlyAfterIdat) {
		boolean idatDone = currentChunkGroup >= ChunksList.CHUNK_GROUP_4_IDAT;
		if (onlyAfterIdat && reader.getCurrentChunkGroup() < ChunksList.CHUNK_GROUP_6_END)
			throw new PngjException("tried to copy last chunks but reader has not ended");
		for (PngChunk chunk : reader.getChunksList().getChunks()) {
			int group = chunk.getChunkGroup();
			if (group < ChunksList.CHUNK_GROUP_4_IDAT && idatDone)
				continue;
			boolean copy = false;
			if (chunk.crit) {
				if (chunk.id.equals(ChunkHelper.PLTE)) {
					if (imgInfo.indexed && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_PALETTE))
						copy = true;
					if (!imgInfo.greyscale && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL))
						copy = true;
				}
			} else { // ancillary
				boolean text = (chunk instanceof PngChunkTextVar);
				boolean safe = chunk.safe;
				// notice that these if are not exclusive
				if (ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL))
					copy = true;
				if (safe && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALL_SAFE))
					copy = true;
				if (chunk.id.equals(ChunkHelper.tRNS)
						&& ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_TRANSPARENCY))
					copy = true;
				if (chunk.id.equals(ChunkHelper.pHYs) && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_PHYS))
					copy = true;
				if (text && ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_TEXTUAL))
					copy = true;
				if (ChunkHelper.maskMatch(copy_mask, ChunkCopyBehaviour.COPY_ALMOSTALL)
						&& !(ChunkHelper.isUnknown(chunk) || text || chunk.id.equals(ChunkHelper.hIST) || chunk.id
								.equals(ChunkHelper.tIME)))
					copy = true;
			}
			if (copy) {
				chunkList.queue(PngChunk.cloneChunk(chunk, imgInfo));
			}
		}
	}

	/**
	 * Copies first (pre IDAT) ancillary chunks from a PngReader.
	 * <p>
	 * Should be called when creating an image from another, before starting writing lines, to copy relevant chunks.
	 * <p>
	 * 
	 * @param reader
	 *            : PngReader object, already opened.
	 * @param copy_mask
	 *            : Mask bit (OR), see <code>ChunksToWrite.COPY_XXX</code> constants
	 */
	public void copyChunksFirst(PngReader reader, int copy_mask) {
		copyChunks(reader, copy_mask, false);
	}

	/**
	 * Copies last (post IDAT) ancillary chunks from a PngReader.
	 * <p>
	 * Should be called when creating an image from another, after writing all lines, before closing the writer, to copy
	 * additional chunks.
	 * <p>
	 * 
	 * @param reader
	 *            : PngReader object, already opened and fully read.
	 * @param copy_mask
	 *            : Mask bit (OR), see <code>ChunksToWrite.COPY_XXX</code> constants
	 */
	public void copyChunksLast(PngReader reader, int copy_mask) {
		copyChunks(reader, copy_mask, true);
	}

	/**
	 * Finalizes the image creation and closes the stream. This MUST be called after writing the lines.
	 */
	public void end() {
		if (rowNum != imgInfo.rows - 1)
			throw new PngjOutputException("all rows have not been written");
		try {
			datStreamDeflated.finish();
			datStream.flush();
			writeLastChunks();
			writeEndChunk();
			if (shouldCloseStream)
				os.close();
		} catch (IOException e) {
			throw new PngjOutputException(e);
		}
	}

	public ChunksListForWrite getChunkList() {
		return chunkList;
	}

	// /// several getters / setters - all this setters are optional

	/**
	 * Filename or description, from the optional constructor argument.
	 */
	public String getFilename() {
		return filename;
	}

	public PngMetadata getMetadata() {
		return metadata;
	}

	/**
	 * Sets compression level of ZIP algorithm.
	 * <p>
	 * This must be called just after constructor, before starting writing.
	 * <p>
	 * See also setFilterType()
	 * 
	 * @param compLevel
	 *            between 0 and 9 (default:6 , recommended: 6 or more)
	 */
	public void setCompLevel(int compLevel) {
		if (compLevel < 0 || compLevel > 9)
			throw new PngjException("Compression level invalid (" + compLevel + ") Must be 0..9");
		this.compLevel = compLevel;
	}

	/**
	 * Sets internal prediction filter type, or strategy to choose it.
	 * <p>
	 * This must be called just after constructor, before starting writing.
	 * <p>
	 * See also setCompLevel()
	 * 
	 * @param filterType
	 *            One of the five prediction types or strategy to choose it (see <code>PngFilterType</code>) Recommended
	 *            values: DEFAULT (default) or AGGRESIVE
	 */
	public void setFilterType(FilterType filterType) {
		filterStrat = new FilterWriteStrategy(imgInfo, filterType);
	}

	/**
	 * Sets maximum size of IDAT fragments. This has little effect on performance you should rarely call this
	 * <p>
	 * 
	 * @param idatMaxSize
	 *            default=0 : use defaultSize (32K)
	 */
	public void setIdatMaxSize(int idatMaxSize) {
		this.idatMaxSize = idatMaxSize;
	}

	/**
	 * if true, input stream will be closed after ending write
	 * <p>
	 * default=true
	 */
	public void setShouldCloseStream(boolean shouldCloseStream) {
		this.shouldCloseStream = shouldCloseStream;
	}

	/**
	 * Writes line, checks that the row number is consistent with that of the ImageLine See writeRow(int[] newrow, int
	 * rown)
	 * 
	 * @deprecated Better use writeRow(ImageLine imgline, int rownumber)
	 */
	public void writeRow(ImageLine imgline) {
		writeRow(imgline.scanline, imgline.getRown());
	}

	/**
	 * Writes line. See writeRow(int[] newrow, int rown)
	 */
	public void writeRow(ImageLine imgline, int rownumber) {
		writeRow(imgline.scanline, rownumber);
	}

	/**
	 * Same as writeRow(int[] newrow, int rown), but does not check row number
	 * 
	 * @param newrow
	 */
	public void writeRow(int[] newrow) {
		writeRow(newrow, -1);
	}

	/**
	 * Writes a full image row. This must be called sequentially from n=0 to n=rows-1 One integer per sample , in the
	 * natural order: R G B R G B ... (or R G B A R G B A... if has alpha) The values should be between 0 and 255 for 8
	 * bitspc images, and between 0- 65535 form 16 bitspc images (this applies also to the alpha channel if present) The
	 * array can be reused.
	 * 
	 * @param newrow
	 *            Array of pixel values
	 * @param rown
	 *            Row number, from 0 (top) to rows-1 (bottom). This is just used as a check. Pass -1 if you want to
	 *            autocompute it
	 */
	public void writeRow(int[] newrow, int rown) {
		if (rown == 0) {
			init();
		}
		if (rown < -1 || rown > imgInfo.rows)
			throw new RuntimeException("invalid value for row " + rown);
		rowNum++;
		if (rown >= 0 && rowNum != rown)
			throw new RuntimeException("rows must be written in strict consecutive order: tried to write row " + rown
					+ ", expected=" + rowNum);
		scanline = newrow;
		// swap
		byte[] tmp = rowb;
		rowb = rowbprev;
		rowbprev = tmp;
		convertRowToBytes();
		filterRow(rown);
		try {
			datStreamDeflated.write(rowbfilter, 0, imgInfo.bytesPerRow + 1);
		} catch (IOException e) {
			throw new PngjOutputException(e);
		}
	}

	public void setDeflaterStrategy(int deflaterStrategy) {
		this.deflaterStrategy = deflaterStrategy;
	}

}