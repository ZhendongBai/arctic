/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.utils;

import com.netease.arctic.io.ArcticFileIO;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

import java.util.Collections;
import java.util.Set;

public class FileUtil {

  /**
   * Parse file name form file path
   *
   * @param filePath file path
   * @return file name parsed from file path
   */
  public static String getFileName(String filePath) {
    int lastSlash = filePath.lastIndexOf('/');
    return filePath.substring(lastSlash + 1);
  }

  /**
   * Parse file directory path from file path
   *
   * @param filePath file path
   * @return file directory path parsed from file path
   */
  public static String getFileDir(String filePath) {
    int lastSlash = filePath.lastIndexOf('/');
    return filePath.substring(0, lastSlash);
  }

  public static void deleteEmptyDirectory(ArcticFileIO io, String directoryPath) {
    deleteEmptyDirectory(io, directoryPath, Collections.emptySet());
  }

  /**
   * Try to recursiveDelete the empty directory
   *
   * @param io   arcticTableFileIo
   * @param directoryPath directory location
   * @param exclude the directory will not be deleted
   */
  public static void deleteEmptyDirectory(ArcticFileIO io, String directoryPath, Set<String> exclude) {
    Preconditions.checkArgument(io.exists(directoryPath), "The target directory is not exist");
    Preconditions.checkArgument(io.isDirectory(directoryPath), "The target path is not directory");
    String parent = new Path(directoryPath).getParent().toString();
    if (exclude.contains(directoryPath) || exclude.contains(parent)) {
      return;
    }

    if (io.isEmptyDirectory(directoryPath)) {
      io.deleteFileWithResult(directoryPath, true);
      deleteEmptyDirectory(io, parent, exclude);
    }
  }
}
