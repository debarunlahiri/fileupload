<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %> <%@
taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta http-equiv="X-UA-Compatible" content="IE=edge" />
    <title>SAF0101 - File Upload</title>
    <link rel="stylesheet" href="<c:url value='/css/fileuploader.css'/>" />
    <style>
      body {
        font-family: Arial, sans-serif;
        font-size: 13px;
        margin: 20px;
        color: #222;
      }
      h2 {
        font-size: 15px;
        margin: 18px 0 6px;
      }
      .row {
        margin-bottom: 10px;
      }
      .row input[type="text"] {
        width: 300px;
        padding: 3px;
      }
      .mng-table {
        width: 720px;
      }
      .mng-table tr.mng-row {
        cursor: pointer;
      }
      .mng-table tr.mng-row:hover td {
        background: #f0f6ff;
      }
      .mng-table tr.mng-cur td {
        font-weight: bold;
      }
      .mng-table td.mng-files {
        background: #fafafa;
      }
      .mng-table td.mng-files a {
        display: block;
        color: #0645ad;
        cursor: pointer;
      }
    </style>
  </head>
  <body>
    <form id="mainForm" method="post" action="<c:url value='/saf0101/save'/>">
      <input type="hidden" name="id" value="<c:out value='${vo.id}'/>" />
      <%-- ONE number shared by both upload boxes --%>
      <input
        type="hidden"
        id="fileMngNo"
        name="fileMngNo"
        value="<c:out value='${vo.fileMngNo}'/>"
      />

      <div class="row">
        Title:
        <input type="text" name="title" value="<c:out value='${vo.title}'/>" />
      </div>

      <h2>Evidence</h2>
      <div id="evBox"></div>

      <h2>Action Taken Evidence</h2>
      <div id="acBox"></div>

      <button type="button" class="fu-btn" id="btnSave">Save</button>
      <button type="button" class="fu-btn" id="btnReject">Reject</button>
    </form>

    <h2>File Manager No. List</h2>
    <table class="fu-table mng-table" id="mngList">
      <thead>
        <tr>
          <th style="width: 150px">fileMngNo</th>
          <th style="width: 70px">Files</th>
          <th style="width: 100px">Total size</th>
          <th style="width: 150px">Uploaded</th>
          <th>Used by</th>
        </tr>
      </thead>
      <tbody></tbody>
    </table>
    <div class="fu-status" id="mngMsg"></div>

    <script src="<c:url value='/js/fileuploader.js'/>"></script>
    <script src="<c:url value='/js/saf0101.js'/>"></script>
    <script>
      // context path for the upload/list/delete/download endpoints
      GridFileUploader.baseUrl =
        '<c:out value="${pageContext.request.contextPath}"/>';
    </script>
  </body>
</html>
