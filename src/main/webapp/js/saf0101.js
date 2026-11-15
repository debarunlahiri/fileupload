/* Page script for saf0101.jsp  (ES5 / IE11) */
var ev, ac;

window.onload = function () {
  ev = new GridFileUploader({
    containerId: "evBox",
    title: "Evidence",
    onChange: loadMngList,
  });
  ac = new GridFileUploader({
    containerId: "acBox",
    title: "Action Taken Evidence",
    onChange: loadMngList,
  });

  // both boxes share ONE fileMngNo: whichever uploads first creates it, the other reuses it
  ev.link(ac);

  // number rendered by the server into the hidden input ('' = new record)
  var no = document.getElementById("fileMngNo").value;
  ev.setFile({ fileMngNo: no, pathKey: "EVIDENCE", pgmId: "SAF0101" });
  ac.setFile({ fileMngNo: no, pathKey: "ACTION_EVIDENCE", pgmId: "SAF0101" });

  document.getElementById("btnSave").onclick = save;
  document.getElementById("btnReject").onclick = rejectAll;
  loadMngList();
};

function currentNo() {
  return ev.getFileMngNo() || ac.getFileMngNo();
}

function save() {
  // an upload may still be running - don't lose its file
  if (ev.isBusy() || ac.isBusy()) {
    alert("Please wait until the uploads finish.");
    return;
  }
  document.getElementById("fileMngNo").value = currentNo();
  document.forms[0].submit();
}

/* Reject (page level): the files of the shared number are copied under ONE new
 * fileMngNo and both boxes switch to it. It is stored when Save is pressed. */
function rejectAll() {
  if (ev.isBusy() || ac.isBusy()) {
    alert("Please wait until the uploads finish.");
    return;
  }
  var src = ev.hasFiles() ? ev : ac.hasFiles() ? ac : null;
  if (!src) {
    alert("There are no files to reject.");
    return;
  }
  if (
    !window.confirm(
      "Reject the uploaded files and re-upload them under a new file number?",
    )
  ) {
    return;
  }
  var btn = document.getElementById("btnReject");
  btn.disabled = true;
  src.reject(true, function () {
    btn.disabled = false;
  });
}

/* ================= fileMngNo list (bottom of page) ================= */

function fileApi(path) {
  return (GridFileUploader.baseUrl || "") + "/core/file/" + path;
}

function getJson(url, cb) {
  var x = new XMLHttpRequest();
  x.open("GET", url, true);
  x.onreadystatechange = function () {
    if (x.readyState !== 4) {
      return;
    }
    try {
      var t = x.responseText;
      var res = JSON.parse(t.substring(t.indexOf("{"), t.lastIndexOf("}") + 1));
      if (x.status !== 200 || res.ErrorCode === -1) {
        cb(res.ErrorMsg || "HTTP " + x.status);
      } else {
        cb(null, res);
      }
    } catch (e) {
      cb("Invalid server response");
    }
  };
  x.send(null);
}

function fmtBytes(b) {
  if (!b) {
    return "0 Bytes";
  }
  var s = ["Bytes", "KB", "MB", "GB", "TB"],
    i = Math.floor(Math.log(b) / Math.log(1024));
  return parseFloat((b / Math.pow(1024, i)).toFixed(2)) + " " + s[i];
}

function cell(tr, text, cls) {
  var td = document.createElement("td");
  if (cls) {
    td.className = cls;
  }
  td.appendChild(document.createTextNode(text));
  tr.appendChild(td);
  return td;
}

var openMngNo = null; // number whose files are expanded

function loadMngList() {
  getJson(fileApi("listMngNo.do"), function (err, res) {
    var msg = document.getElementById("mngMsg");
    var tbody = document
      .getElementById("mngList")
      .getElementsByTagName("tbody")[0];
    if (err) {
      msg.className = "fu-status fu-err";
      msg.innerHTML = "";
      msg.appendChild(document.createTextNode(err));
      return;
    }
    msg.innerHTML = "";
    tbody.innerHTML = "";

    var list = res.DS_MNG_NO_LIST || [];
    if (!list.length) {
      var e = document.createElement("tr");
      var td = cell(e, "No files uploaded yet.");
      td.colSpan = 5;
      tbody.appendChild(e);
      return;
    }
    var curNo = currentNo();
    for (var i = 0; i < list.length; i++) {
      var r = list[i],
        tr = document.createElement("tr"),
        used = [];
      tr.className = "mng-row";
      if (r.FILE_MNG_NO === curNo) {
        used.push("Current record (Evidence + Action Taken Evidence)");
      }
      if (used.length) {
        tr.className += " mng-cur";
      }
      cell(tr, r.FILE_MNG_NO);
      cell(tr, String(r.FILE_CNT), "fu-c");
      cell(tr, fmtBytes(r.TOTAL_SIZE), "fu-c");
      cell(tr, r.REG_DT || "", "fu-c");
      cell(tr, used.join(", "));
      tr.onclick = (function (no, row) {
        return function () {
          toggleFiles(no, row);
        };
      })(r.FILE_MNG_NO, tr);
      tbody.appendChild(tr);
      if (openMngNo === r.FILE_MNG_NO) {
        showFiles(r.FILE_MNG_NO, tr);
      }
    }
  });
}

function removeDetail() {
  var old = document.getElementById("mngDetail");
  if (old && old.parentNode) {
    old.parentNode.removeChild(old);
  }
}

function toggleFiles(no, row) {
  if (openMngNo === no) {
    openMngNo = null;
    removeDetail();
    return;
  }
  openMngNo = no;
  showFiles(no, row);
}

function showFiles(no, row) {
  getJson(
    fileApi("listFile.do") + "?fileMngNo=" + encodeURIComponent(no),
    function (err, res) {
      if (openMngNo !== no) {
        return;
      }
      removeDetail();
      var tr = document.createElement("tr");
      tr.id = "mngDetail";
      var td = document.createElement("td");
      td.colSpan = 5;
      td.className = "mng-files";
      var files = err ? [] : res.DS_FILE_UPLOAD_LIST || [];
      if (err) {
        td.appendChild(document.createTextNode(err));
      }
      for (var i = 0; i < files.length; i++) {
        var a = document.createElement("a");
        a.appendChild(
          document.createTextNode(
            (files[i].PATH_KEY ? "[" + files[i].PATH_KEY + "] " : "") +
              files[i].LOGI_FILE_NM +
              "  (" +
              fmtBytes(files[i].FILE_SIZE) +
              ")",
          ),
        );
        a.onclick = (function (f) {
          return function (e) {
            if (e && e.stopPropagation) {
              e.stopPropagation();
            }
            var fr = document.createElement("iframe");
            fr.style.display = "none";
            fr.src =
              fileApi("downloadFile.do") +
              "?fileRegNo=" +
              encodeURIComponent(f.FILE_REG_NO) +
              "&fileMngNo=" +
              encodeURIComponent(f.FILE_MNG_NO);
            document.body.appendChild(fr);
            setTimeout(function () {
              if (fr.parentNode) {
                fr.parentNode.removeChild(fr);
              }
            }, 30000);
          };
        })(files[i]);
        td.appendChild(a);
      }
      tr.appendChild(td);
      if (row.nextSibling) {
        row.parentNode.insertBefore(tr, row.nextSibling);
      } else {
        row.parentNode.appendChild(tr);
      }
    },
  );
}
