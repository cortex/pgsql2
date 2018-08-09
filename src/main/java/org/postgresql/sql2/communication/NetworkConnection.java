package org.postgresql.sql2.communication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.postgresql.sql2.PGConnectionProperties;
import org.postgresql.sql2.buffer.ByteBufferPool;
import org.postgresql.sql2.buffer.ByteBufferPoolOutputStream;
import org.postgresql.sql2.buffer.PooledByteBuffer;
import org.postgresql.sql2.communication.packets.ErrorResponse;
import org.postgresql.sql2.communication.packets.ParameterStatus;
import org.postgresql.sql2.communication.packets.parts.ErrorResponseField;
import org.postgresql.sql2.execution.NioService;
import org.postgresql.sql2.execution.NioServiceContext;

import jdk.incubator.sql2.ConnectionProperty;

public class NetworkConnection implements NioService, NetworkConnectContext, NetworkWriteContext, NetworkReadContext {

  private final Map<ConnectionProperty, Object> properties;

  private final NioServiceContext context;

  private final ByteBufferPoolOutputStream outputStream;

  private final SocketChannel socketChannel;

  private final Queue<NetworkRequest> priorityRequestQueue = new LinkedList<>();

  private final Queue<NetworkRequest> requestQueue = new ConcurrentLinkedQueue<>();

  private final Queue<NetworkResponse> awaitingResponses = new LinkedList<>();

  private final BEFrameParser parser = new BEFrameParser();

  private final PreparedStatementCache preparedStatementCache = new PreparedStatementCache();

  private NetworkConnect connect = null;

  /**
   * Possible blocking {@link NetworkResponse}.
   */
  private NetworkResponse blockingResponse = new NetworkResponse() {
    @Override
    public NetworkResponse read(NetworkReadContext context) throws IOException {
      throw new IllegalStateException("Should not read until connected");
    }
  };

  /**
   * Instantiate.
   * 
   * @param properties Connection properties.
   * @param context    {@link NioServiceContext}.
   * @param bufferPool {@link ByteBufferPool}.
   */
  public NetworkConnection(Map<ConnectionProperty, Object> properties, NioServiceContext context,
      ByteBufferPool bufferPool) {
    this.properties = properties;
    this.context = context;
    this.outputStream = new ByteBufferPoolOutputStream(bufferPool);
    this.socketChannel = (SocketChannel) context.getChannel();
  }

  /**
   * Sends the {@link NetworkConnect}.
   * 
   * @param networkConnect {@link NetworkConnect}.
   */
  public void sendNetworkConnect(NetworkConnect networkConnect) {

    synchronized (this.socketChannel.blockingLock()) {

      // Ensure only one connect
      if (this.connect != null) {
        throw new IllegalStateException("Connection already being established");
      }
      this.connect = networkConnect;

      // Initialise the network request
      try {
        networkConnect.connect(this);
      } catch (IOException ex) {
        networkConnect.handleException(ex);
      }
    }
  }

  /**
   * Sends the {@link NetworkRequest}.
   * 
   * @param request {@link NetworkRequest}.
   */
  public void sendNetworkRequest(NetworkRequest request) {

    synchronized (this.socketChannel.blockingLock()) {

      // Ready network request for writing
      this.requestQueue.add(request);
      this.context.writeRequired();
    }
  }

  /**
   * Indicates if the connection is closed.
   * 
   * @return <code>true</code> if the connection is closed.
   */
  public boolean isConnectionClosed() {
    return !socketChannel.isConnected();
  }

  /*
   * =============== NioService =====================
   */

  @Override
  public void handleConnect() throws Exception {

    synchronized (this.socketChannel.blockingLock()) {

      // Specify to write immediately
      NetworkRequest initialRequest = this.connect.finishConnect(this);

      // As connected, may now start writing
      this.blockingResponse = null;

      // Load initial action to be undertaken first
      if (initialRequest != null) {

        // Run initial request
        Queue<NetworkRequest> queue = new LinkedList<>();
        queue.add(initialRequest);
        this.handleWrite(queue);
      }
    }
  }

  @Override
  public void handleWrite() throws Exception {
    this.handleWrite(this.requestQueue);
  }

  /**
   * Flushes the {@link NetworkRequest} instances to {@link PooledByteBuffer}
   * instances.
   * 
   * @param requests {@link Queue} of {@link NetworkRequest} instances.
   * @return <code>true</code> if to block.
   * @throws Exception If fails to flush {@link NetworkRequest} instances.
   */
  private boolean flushRequests(Queue<NetworkRequest> requests) throws Exception {

    // Flush out the request
    NetworkRequest request;
    while ((request = requests.poll()) != null) {

      // Flush the request
      NetworkRequest nextRequest;
      do {
        nextRequest = request.write(this);

        // Determine if requires response
        NetworkResponse response = request.getRequiredResponse();
        if (response != null) {
          this.awaitingResponses.add(response);
        }

        // Determine if request blocks for further interaction
        if (request.isBlocking()) {
          this.blockingResponse = response;
          return true; // can not send further requests
        }

        // Loop until all next requests flushed
        request = nextRequest;
      } while (request != null);
    }

    // As here, all flushed with no blocking
    return false;
  }

  /**
   * Possible previous incomplete {@link PooledByteBuffer} not completely written.
   */
  private PooledByteBuffer incompleteWriteBuffer = null;

  /**
   * Handles writing the {@link NetworkRequest} instances.
   * 
   * @param requests {@link Queue} of {@link NetworkRequest} instances.
   * @throws Exception If fails to write the {@link NetworkRequest} instances.
   */
  private void handleWrite(Queue<NetworkRequest> requests) throws Exception {

    // Only flush further requests if no blocking response
    if (this.blockingResponse == null) {

      // Flush out the requests (doing priority queue first)
      if (!this.flushRequests(this.priorityRequestQueue)) {
        this.flushRequests(requests);
      }
    }

    // Write the previous incomplete write buffer
    if (this.incompleteWriteBuffer != null) {
      this.outputStream.write(this.incompleteWriteBuffer.getByteBuffer());
      if (this.incompleteWriteBuffer.getByteBuffer().hasRemaining()) {
        // Further writes required
        this.context.setInterestedOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        return;
      }
      this.incompleteWriteBuffer.release();
      this.incompleteWriteBuffer = null;
    }

    // Write data to network
    List<PooledByteBuffer> writtenBuffers = this.outputStream.getWrittenBuffers();
    for (int i = 0; i < writtenBuffers.size(); i++) {
      PooledByteBuffer pooledBuffer = writtenBuffers.get(i);
      ByteBuffer byteBuffer = pooledBuffer.getByteBuffer();

      // Write the buffer
      byteBuffer.flip();
      this.socketChannel.write(byteBuffer);
      if (byteBuffer.hasRemaining()) {
        // Socket buffer full (clear written buffers)
        this.incompleteWriteBuffer = pooledBuffer;
        this.outputStream.removeBuffers(i);
        this.context.setInterestedOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        return;
      }

      // Buffer written so release
      pooledBuffer.release();
    }

    // As here all data written
    writtenBuffers.clear();
    this.context.setInterestedOps(SelectionKey.OP_READ);
  }

  /**
   * {@link BEFrame} for {@link NetworkReadContext}.
   */
  private BEFrame beFrame = null;

  /**
   * Allows {@link NetworkReadContext} to specify if write required.
   */
  private boolean isWriteRequired = false;

  /**
   * Immediate {@link NetworkResponse}.
   */
  private NetworkResponse immediateResponse = null;

  @Override
  public void handleRead() throws IOException {

    // TODO use pooled byte buffers
    ByteBuffer readBuffer = ByteBuffer.allocate(1024);

    // Reset for reads
    int bytesRead = -1;
    this.isWriteRequired = false;
    try {

      // Consume data on the socket
      while ((bytesRead = this.socketChannel.read(readBuffer)) > 0) {

        // Setup for consuming parts
        readBuffer.flip();
        int position = 0;

        // Service the BE frames
        BEFrame frame;
        while ((frame = this.parser.parseBEFrame(readBuffer, position, bytesRead)) != null) {
          position += this.parser.getConsumedBytes();

          // Hook in any notifications
          switch (frame.getTag()) {

          case PARAM_STATUS:
            // Load parameters for connection
            ParameterStatus paramStatus = new ParameterStatus(frame.getPayload());
            this.properties.put(PGConnectionProperties.lookup(paramStatus.getName()), paramStatus.getValue());
            break;

          case CANCELLATION_KEY_DATA:
            // TODO handle cancellation key
            break;

          case READY_FOR_QUERY:
            // TODO handle ready for query
            break;

          case ERROR_RESPONSE:
            // TODO this should be handled specific to NetworkResponse
            ErrorResponse error = new ErrorResponse(frame.getPayload());
            String message = error.getField(ErrorResponseField.Types.MESSAGE);
            System.out.println("ERROR: " + message);
            break;

          default:
            // Obtain the awaiting response
            NetworkResponse awaitingResponse;
            if (this.immediateResponse != null) {
              awaitingResponse = this.immediateResponse;
              this.immediateResponse = null;
            } else {
              awaitingResponse = this.awaitingResponses.poll();
            }

            // Provide frame to awaiting response
            this.beFrame = frame;
            this.immediateResponse = awaitingResponse.read(this);

            // Remove if blocking writing
            if (awaitingResponse == this.blockingResponse) {
              this.blockingResponse = null;

              // Flag to write (as very likely have writes)
              this.isWriteRequired = true;
            }
          }
        }

        // Clear buffer for re-use
        readBuffer.clear();
      }
    } catch (NotYetConnectedException | ClosedChannelException ignore) {
      ignore.printStackTrace();
      throw ignore;
    } finally {
      if (isWriteRequired) {
        this.context.writeRequired();
      }
    }
    if (bytesRead < 0) {
      throw new ClosedChannelException();
    }
  }

  @Override
  public void handleException(Throwable ex) {
    // TODO consider how to handle exception
    ex.printStackTrace();
  }

  /*
   * ========== NetworkRequestInitialiseContext ======================
   */

  @Override
  public SocketChannel getSocketChannel() {
    return this.socketChannel;
  }

  @Override
  public Map<ConnectionProperty, Object> getProperties() {
    return this.properties;
  }

  /*
   * ============ NetworkRequestReadContext ==========================
   */

  @Override
  public BEFrame getBEFrame() {
    return this.beFrame;
  }

  @Override
  public void write(NetworkRequest request) {
    this.priorityRequestQueue.add(request);
    this.isWriteRequired = true;
  }

  @Override
  public void writeRequired() {
    this.isWriteRequired = true;
  }

  /*
   * ============ NetworkRequestWriteContext ==========================
   */

  @Override
  public NetworkOutputStream getOutputStream() {
    return this.outputStream;
  }

  @Override
  public PreparedStatementCache getPreparedStatementCache() {
    return this.preparedStatementCache;
  }

}