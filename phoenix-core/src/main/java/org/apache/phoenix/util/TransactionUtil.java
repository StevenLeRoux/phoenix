/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.util;

import java.io.IOException;
import java.sql.SQLException;

import co.cask.tephra.Transaction;
import co.cask.tephra.TransactionCodec;
import co.cask.tephra.TransactionConflictException;
import co.cask.tephra.TransactionFailureException;
import co.cask.tephra.TxConstants;
import co.cask.tephra.hbase98.TransactionAwareHTable;

import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PTable;

public class TransactionUtil {
    private TransactionUtil() {
    }
    
    public static long convertToNanoseconds(long serverTimeStamp) {
        return serverTimeStamp * 1000000;
    }
    
    public static long convertToMillisecods(Long serverTimeStamp) {
        return serverTimeStamp / 1000000;
    }
    
    public static SQLException getSQLException(TransactionFailureException e) {
        if (e instanceof TransactionConflictException) { 
            return new SQLExceptionInfo.Builder(SQLExceptionCode.TRANSACTION_CONFLICT_EXCEPTION)
                .setMessage(e.getMessage())
                .setRootCause(e)
                .build().buildException();

        }
        return new SQLExceptionInfo.Builder(SQLExceptionCode.TRANSACTION_EXCEPTION)
            .setMessage(e.getMessage())
            .setRootCause(e)
            .build().buildException();
    }
    
    public static TransactionAwareHTable getTransactionAwareHTable(HTableInterface htable, PTable table) {
    	// Conflict detection is not needed for tables with write-once/append-only data
    	return new TransactionAwareHTable(htable, table.isImmutableRows() ? TxConstants.ConflictDetection.NONE : TxConstants.ConflictDetection.ROW);
    }
    
	public static long getResolvedTimestamp(PhoenixConnection connection, boolean isTransactional, Long defaultResolvedTimestamp) {
		MutationState mutationState = connection.getMutationState();
		Long scn = connection.getSCN();
	    return scn != null ?  scn : (isTransactional && mutationState.isTransactionStarted()) ? convertToMillisecods(mutationState.getReadPointer()) : defaultResolvedTimestamp;
	}

	public static long getResolvedTime(PhoenixConnection connection, MetaDataMutationResult result) {
		PTable table = result.getTable();
		boolean isTransactional = table!=null && table.isTransactional();
		return getResolvedTimestamp(connection, isTransactional, result.getMutationTime());
	}

	public static long getTableTimestamp(PhoenixConnection connection, MetaDataMutationResult result) {
		PTable table = result.getTable();
		MutationState mutationState = connection.getMutationState();
		boolean txInProgress = table != null && table.isTransactional() && mutationState.isTransactionStarted();
		return  txInProgress ? convertToMillisecods(mutationState.getReadPointer()) : result.getMutationTime();
	}

	public static Long getTableTimestamp(PhoenixConnection connection, boolean transactional, Long mutationTime) throws SQLException {
		Long timestamp = mutationTime;
		MutationState mutationState = connection.getMutationState();
		if (transactional && !mutationState.isTransactionStarted() && connection.getSCN()==null) {
			mutationState.startTransaction();
			timestamp = convertToMillisecods(mutationState.getReadPointer());
			connection.commit();
		}
		return timestamp;
	}
}
