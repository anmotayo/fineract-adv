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
package org.apache.fineract.infrastructure.bulkimport.service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.bulkimport.data.BulkImportValidationResultData;
import org.apache.fineract.infrastructure.bulkimport.importhandler.journalentry.JournalEntriesImportHandler;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JournalEntriesBulkImportWorkbookValidationServiceImpl implements JournalEntriesBulkImportWorkbookValidationService {

    private final JournalEntriesImportHandler journalEntriesImportHandler;

    @Override
    public BulkImportValidationResultData validateWorkbook(final InputStream inputStream, final FormDataContentDisposition fileDetail,
            final String locale, final String dateFormat) {
        try {
            if (inputStream != null && fileDetail != null && locale != null && dateFormat != null) {
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                inputStream.transferTo(baos);
                final byte[] bytes = baos.toByteArray();
                final BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(bytes));
                final Tika tika = new Tika();
                final TikaInputStream tikaInputStream = TikaInputStream.get(bis);
                final String fileType = tika.detect(tikaInputStream);
                if (!fileType.contains("msoffice") && !fileType.contains("application/vnd.ms-excel")) {
                    throw new GeneralPlatformDomainRuleException("error.msg.invalid.file.extension",
                            "Uploaded file extension is not recognized.");
                }

                try (Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    return this.journalEntriesImportHandler.validate(workbook, locale, dateFormat);
                }
            }
            throw new GeneralPlatformDomainRuleException("error.msg.null", "One or more of the given parameters not found");
        } catch (IOException e) {
            log.error("Problem occurred in validateWorkbook function", e);
            throw new GeneralPlatformDomainRuleException("error.msg.io.exception",
                    "IO exception occured with " + fileDetail.getFileName() + " " + e.getMessage(), e);
        }
    }
}
