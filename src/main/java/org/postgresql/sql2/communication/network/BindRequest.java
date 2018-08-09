package org.postgresql.sql2.communication.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.postgresql.sql2.communication.FEFrame;
import org.postgresql.sql2.communication.NetworkOutputStream;
import org.postgresql.sql2.communication.NetworkRequest;
import org.postgresql.sql2.communication.NetworkResponse;
import org.postgresql.sql2.communication.NetworkWriteContext;
import org.postgresql.sql2.communication.PreparedStatementCache;
import org.postgresql.sql2.operations.helpers.FEFrameSerializer;
import org.postgresql.sql2.operations.helpers.ParameterHolder;
import org.postgresql.sql2.operations.helpers.QueryParameter;
import org.postgresql.sql2.submissions.RowSubmission;
import org.postgresql.sql2.util.BinaryHelper;

/**
 * Bind {@link NetworkRequest}.
 * 
 * @author Daniel Sagenschneider
 */
public class BindRequest<T> implements NetworkRequest {

  private final Portal portal;

  public BindRequest(Portal portal) {
    this.portal = portal;
  }

  /*
   * ================= NetworkRequest =========================
   */

  @Override
  public NetworkRequest write(NetworkWriteContext context) throws Exception {
    
    // Obtain the query details
    String portalName = this.portal.getPortalName();
    String queryName = this.portal.getQuery().getQueryName();
    String sql = this.portal.getSql();
    ParameterHolder holder = this.portal.getParameterHolder();

    // Write the packet
    NetworkOutputStream wire = context.getOutputStream();
    wire.write(FEFrame.FrontendTag.BIND.getByte());
    wire.initPacket();
    wire.write(this.portal.getPortalName());
    wire.write(this.portal.getQuery().getQueryName());
    wire.write(BinaryHelper.writeShort(holder.size()));
    for (QueryParameter qp : holder.parameters()) {
      wire.write(BinaryHelper.writeShort(qp.getParameterFormatCode()));
    }
    wire.write(BinaryHelper.writeShort(holder.size()));
    int paramIndex = 0;
    for (QueryParameter qp : holder.parameters()) {
      byte[] paramData = qp.getParameter(paramIndex++);
      if (paramData.length == 0) { // handling the null special case
        wire.write(BinaryHelper.writeInt(-1));
      } else {
        wire.write(BinaryHelper.writeInt(paramData.length));
        wire.write(paramData);
      }
    }
    wire.writeTerminator();
    wire.writeTerminator();
    wire.completePacket();

    // Next step to execute
    return new ExecuteRequest<>(this.portal);
  }

  @Override
  public NetworkResponse getRequiredResponse() {
    return new BindResponse();
  }

}