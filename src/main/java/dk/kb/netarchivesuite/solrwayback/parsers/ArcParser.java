package dk.kb.netarchivesuite.solrwayback.parsers;

import dk.kb.netarchivesuite.solrwayback.interfaces.ArcSource;
import dk.kb.netarchivesuite.solrwayback.service.dto.ArcEntry;
import dk.kb.netarchivesuite.solrwayback.util.DateUtils;
import dk.kb.netarchivesuite.solrwayback.util.InputStreamUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public class ArcParser extends  ArcWarcFileParserAbstract{

  private static final Logger log = LoggerFactory.getLogger(ArcParser.class);
  public static final String newLineChar ="\r\n"; //This is warc header ending, but the header is also only used for display or warc export
  public static String ARC_HEADER_ENCODING ="ISO-8859-1";
  /*
   *Header example:
   *http://www.radionyt.dk/forum/Default.asp?mode=message&Id=10846&ForumId=31 86.58.185.215 20090610094553 text/html 35257
   *HTTP/1.1 200 OK
   *Cache-Control: private
   *Connection: close
   *Date: Wed, 10 Jun 2009 09:45:54 GMT
   *Content-Length: 35025
   *Content-Type: text/html
   *Content-Encoding: gzip 
   *Server: Microsoft-IIS/6.0
   *MicrosoftOfficeWebServer: 5.0_Pub
   *X-Powered-By: ASP.NET
   */
  public static ArcEntry getArcEntry(ArcSource arcSource, long arcEntryPosition) throws Exception {
      if (arcSource.getSource().toLowerCase(Locale.ROOT).endsWith(".gz")){ //It is zipped
        return getArcEntryZipped(arcSource, arcEntryPosition);
      } else {
          return getArcEntryNotZipped(arcSource, arcEntryPosition);
      }      
  }

  
  public static ArcEntry getArcEntryNotZipped(ArcSource arcSource, long arcEntryPosition) throws Exception {
      ArcEntry arcEntry = new ArcEntry();
      arcEntry.setFormat(ArcEntry.FORMAT.ARC);
      arcEntry.setSource(arcSource);
      arcEntry.setOffset(arcEntryPosition);

      try (InputStream is = arcSource.get()) {
          InputStreamUtils.skipFully(is, arcEntryPosition);

          try (BufferedInputStream bis = new BufferedInputStream(is)) {
              loadArcHeader(bis, arcEntry);

              //log.debug("Arc entry : totalsize:"+totalSize +" headersize:"+headerSize+" binary size:"+binarySize);
          }
          return arcEntry;
      }
  }

  public static ArcEntry getArcEntryZipped(ArcSource arcSource, long arcEntryPosition) throws Exception {

    ArcEntry arcEntry = new ArcEntry();
    arcEntry.setFormat(ArcEntry.FORMAT.ARC);
    arcEntry.setSource(arcSource);
    arcEntry.setOffset(arcEntryPosition);

    try (InputStream is = arcSource.get()) {
        InputStreamUtils.skipFully(is, arcEntryPosition);

        // log.info("file is zipped:"+arcFilePath);
        try (GZIPInputStream stream = new GZIPInputStream(is);
             BufferedInputStream  bis= new BufferedInputStream(stream)) {

            loadArcHeader(bis, arcEntry);

            //System.out.println("Arc entry : totalsize:"+totalSize +" binary size:"+binarySize +" firstHeadersize:"+byteCount);
        }
    }
    return arcEntry;
  }


  /**
   * ARC files does not have multiple ARC headers for entries.
   * ARC entries start with {@code URL timestamp mime length} followed directly by HTTP-headers.
   * <p>
   * This method populated the given {@code arcEntry} with information from the single ARC-specific line as well
   * as information from the HTTP-headers.
   * @param bis stream positioned at the start of an ARC-entry. Afterwards it will be positioned at the start of content.
   * @param arcEntry the ARC entry representation to populate.
   */
  private static void loadArcHeader(BufferedInputStream bis, ArcEntry arcEntry) throws IOException{

    StringBuilder headerLinesBuffer = new StringBuilder();

    String line = readLine(bis); // First line
    headerLinesBuffer.append(line+newLineChar);

    if  (!(line.startsWith("http"))) //No version check yet
    {            
      throw new IllegalArgumentException("ARC header does not start with http : "+line);
    }            
    
    arcEntry.setFileName(getArcLastUrlPart(line));                    
    String waybackDate = getWaybackDate(line);                    
    arcEntry.setCrawlDate(DateUtils.convertWaybackDate2SolrDate(waybackDate));          
    arcEntry.setWaybackDate(waybackDate);
    arcEntry.setUrl(getArcUrl(line));
    arcEntry.setIp(getIp(line));            
      System.out.println(line);

    String[] split = line.split(" ");
    int totalSize = Integer.parseInt(split[split.length - 1]);

    int byteCount=0; //Bytes of second header

    LineAndByteCount lc =readLineCount(bis);
    line=lc.getLine();
    arcEntry.setStatus_code(getStatusCode(line));
    headerLinesBuffer.append(line+newLineChar);
    byteCount +=lc.getByteCount();                    

    while (!"".equals(line)) { // End of warc second header block is an empty line
      lc =readLineCount(bis);
      line=lc.getLine();
      headerLinesBuffer.append(line+newLineChar);
      byteCount +=lc.getByteCount();                    

      populateArcHeader(arcEntry, line);        
    }
    arcEntry.setHeader(headerLinesBuffer.toString());
    
    int binarySize = totalSize-byteCount;                                                   
    arcEntry.setContentLength(binarySize); //trust the load, not the http-header for arc-files
    arcEntry.setBinaryArraySize(binarySize);      
  }


    /**
     * Constructs an ARC neutral {@code InputStream} that delivers the binary content for a ARC entry.
     * If the ARC is marked as gzip-compressed, the content will be automatically gzip-uncompressed.
     * <p>
     * This method does not handle decompression or dechunking outside of basic ARC compression.
     * <p>
     * This method does not cache the binary and the caller should take care to close the returned {@code InputStream}
     * after use as failing to do so might cause resource leaks.
     * @param arcSource        source of the raw ARC.
     * @param arcEntryPosition where in the ARC the entry is positioned.
     * @return a stream with the binary content from a ARC entry.
     * @throws IOException if the binary could not be read.
     */
  public static BufferedInputStream lazyLoadContent(ArcSource arcSource, long arcEntryPosition) throws IOException {
      ArcEntry arcEntry = new ArcEntry(); // We just throw away the header info anyway 

      InputStream is = arcSource.get();
      InputStreamUtils.skipFully(is, arcEntryPosition);

      if (arcSource.getSource().toLowerCase(Locale.ROOT).endsWith(".gz")){ //It is zipped
          // log.info("file is zipped:"+arcFilePath);
          GZIPInputStream zipStream = new GZIPInputStream(is);
          BufferedInputStream  bis = new BufferedInputStream(zipStream);
          loadArcHeader(bis, arcEntry);
          
          BoundedInputStream maxStream = new BoundedInputStream(bis, arcEntry.getBinaryArraySize());
          return new BufferedInputStream(maxStream); // It's a mess to use nested BufferedInputStreams...
          
      } else {
          BufferedInputStream  bis = new BufferedInputStream(is);
          loadArcHeader(bis, arcEntry);
          BoundedInputStream maxStream = new BoundedInputStream(bis, arcEntry.getBinaryArraySize());
          return new BufferedInputStream(maxStream);
      }
      
  }

    public static String readLine(BufferedInputStream bis) throws IOException{
      StringBuffer buf = new StringBuffer();
      int current = 0; // CRLN || LN
      while ((current = bis.read()) != '\r' && current != '\n') {
        buf.append((char) current);
      }
      if (current == '\r') {
        bis.read(); // line ends with 10 13
      }


      return buf.toString();

    }

    public static LineAndByteCount readLineCount(BufferedInputStream  bis) throws IOException {
    int count = 0;
    StringBuffer buf = new StringBuffer();
    int current = 0; // CRLN || LN

    count++; //Also count linefeed
    while ((current = bis.read()) != '\r' && current != '\n') {             
      buf.append((char) current);
      count++;
    }
    if (current == '\r') {
      bis.read(); // line ends with 10 13
      count++;
    }       
    LineAndByteCount lc = new LineAndByteCount();
    lc.setLine(buf.toString());
    lc.setByteCount(count);

    return lc;

  }


  private static String getArcLastUrlPart(String arcHeaderLine) {
    String[] split = arcHeaderLine.split(" ");
    String fullUrl = split[0];
    String paths[] = fullUrl.split("/");
    String fileName = paths[paths.length - 1];
    if (fileName == null){
      fileName="";
    }
    return fileName.trim();
  }


  private static String getArcUrl(String arcHeaderLine) {
    String[] split = arcHeaderLine.split(" ");
    String fullUrl = split[0];
    return fullUrl.trim();
  }

  private static String getIp(String arcHeaderLine) {
    String[] split = arcHeaderLine.split(" ");
    return split[1];
  }

  private static String getWaybackDate(String arcHeaderLine) {
    String[] split = arcHeaderLine.split(" ");
    return split[2];                                
  }

  private static void populateArcHeader(ArcEntry arcEntry, String headerLine) {
    if (headerLine.toLowerCase().startsWith("content-length:")) {      
      String[] split = headerLine.split(":");
      //arcEntry.setContentLength(Integer.parseInt(split[1].trim())); //Dont trust server. Use binary size.        
    } else if (headerLine.toLowerCase().startsWith("content-type:")) {
      //text/html; charset=
      String[] part1 = headerLine.split(":");
      String[] part2= part1[1].split(";");                                  
      arcEntry.setContentType(part2[0].trim());
      
      if (part2.length == 2){
        String charsetString = part2[1].trim();
        if (charsetString.toLowerCase().startsWith("charset=")){
          String  charset= charsetString.substring(8).replace("\"", "");
          arcEntry.setContentCharset( charset);
        }                    
      }
      
    }
    else if (headerLine.toLowerCase().startsWith("content-encoding:")) {
      //text/html; charset=
      String[] contentHeader = headerLine.split(":");                                  
      
      arcEntry.setContentEncoding(contentHeader[1].trim());
      //log.info("setting content encoding:"+contentHeader[1].trim());
    }
    else if (headerLine.toLowerCase().startsWith("location:")) {                                      
      arcEntry.setRedirectUrl(headerLine.substring(9).trim());
    }
    else if (headerLine.toLowerCase().startsWith("transfer-encoding:")) {                                      
        String transferEncoding=headerLine.substring(18).trim();
//        log.debug("transfer-encoding:"+transferEncoding);
        if (transferEncoding.equalsIgnoreCase("chunked")) {
            arcEntry.setChunked(true);
         }
      }    
    
    
  }  
  
}
