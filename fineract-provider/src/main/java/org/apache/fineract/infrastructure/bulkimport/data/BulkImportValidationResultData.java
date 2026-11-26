/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.bulkimport.data;

import java.util.List;

public final class BulkImportValidationResultData {

    private final Integer totalRecords;
    private final Integer successCount;
    private final Integer errorCount;
    private final Boolean valid;
    private final List<BulkImportValidationErrorData> errors;

    public static BulkImportValidationResultData instance(final Integer totalRecords, final Integer successCount, final Integer errorCount,
            final List<BulkImportValidationErrorData> errors) {
        return new BulkImportValidationResultData(totalRecords, successCount, errorCount, errors);
    }

    private BulkImportValidationResultData(final Integer totalRecords, final Integer successCount, final Integer errorCount,
            final List<BulkImportValidationErrorData> errors) {
        this.totalRecords = totalRecords;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.valid = errorCount == 0;
        this.errors = errors;
    }

    public Integer getTotalRecords() {
        return totalRecords;
    }

    public Integer getSuccessCount() {
        return successCount;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public Boolean getValid() {
        return valid;
    }

    public List<BulkImportValidationErrorData> getErrors() {
        return errors;
    }
}
