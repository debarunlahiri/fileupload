/* ============================================================
 * GridFileUploader  (ES5, IE11 compatible, no dependencies)
 *
 * Auto-upload by fileMngNo:
 *  - Files are queued and uploaded ONE AT A TIME.
 *  - Empty fileMngNo: the first upload is sent without it; the server
 *    creates a new FILE_MNG_NO and returns it. That number is reused for
 *    every later file, so all files share one fileMngNo.
 *  - getFileMngNo() always returns the current number.
 *
 * Set the app context path once:  GridFileUploader.baseUrl = '/myapp';
 * ============================================================ */
(function (window, document) {
  "use strict";

  function url(p) {
    return (GridFileUploader.baseUrl || "") + "/core/file/" + p;
  }

  /* ---------- small ES5 helpers ---------- */
  function extend(a, b) {
    var k;
    for (k in b) {
      if (b.hasOwnProperty(k)) {
        a[k] = b[k];
      }
    }
    return a;
  }
  function bind(fn, ctx) {
    return function () {
      return fn.apply(ctx, arguments);
    };
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) {
      e.className = cls;
    }
    if (text !== undefined) {
      e.appendChild(document.createTextNode(text));
    }
    return e;
  }
  function on(node, type, fn) {
    if (node.addEventListener) {
      node.addEventListener(type, fn, false);
    } else {
      node.attachEvent("on" + type, fn);
    }
  }
  function enc(v) {
    return encodeURIComponent(v == null ? "" : v);
  }

  function formatBytes(bytes, decimals) {
    if (!bytes) {
      return "0 Bytes";
    }
    var k = 1024,
      dm = decimals <= 0 ? 0 : decimals || 2;
    var sizes = ["Bytes", "KB", "MB", "GB", "TB"];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + " " + sizes[i];
  }

  /* server may wrap JSON in tags/HTML -> take text between first { and last } */
  function parseResponse(text) {
    var s = text.indexOf("{"),
      e = text.lastIndexOf("}");
    if (s < 0 || e < s) {
      throw new Error("Invalid server response");
    }
    return JSON.parse(text.substring(s, e + 1));
  }

  /* ---------- the component ---------- */
  function GridFileUploader(opts) {
    this.opts = extend(
      { containerId: "", title: "", editable: true, maxSizeMB: 50 },
      opts || {},
    );
    this.fileMngNo = "";
    this.pathKey = "";
    this.pgmId = "";
    this.rows = [];
    this.selected = {};
    this.queue = [];
    this.busy = false;
    this.peers = []; /* controls that share the same fileMngNo */
    this._build();
  }

  GridFileUploader.prototype._build = function () {
    var self = this;
    var box = document.getElementById(this.opts.containerId);
    if (!box) {
      throw new Error("Container not found: " + this.opts.containerId);
    }
    box.innerHTML = "";
    var wrap = el("div", "fu-box");
    this.wrap = wrap;

    var bar = el("div", "fu-toolbar");
    this.btnAdd = el("button", "fu-btn", "Add File");
    this.btnAdd.type = "button";
    this.btnDel = el("button", "fu-btn", "Delete File");
    this.btnDel.type = "button";
    this.input = el("input", "fu-input");
    this.input.type = "file";
    this.input.multiple = true; /* IE10+ */
    bar.appendChild(this.btnAdd);
    bar.appendChild(this.btnDel);
    bar.appendChild(this.input);
    if (this.opts.title) {
      var t = el("strong", "", this.opts.title + " ");
      wrap.appendChild(t);
    }
    wrap.appendChild(bar);

    this.table = el("table", "fu-table");
    this.table.innerHTML =
      '<thead><tr><th style="width:24px"></th><th>File name</th>' +
      '<th style="width:80px">Size</th><th style="width:110px">Status</th></tr></thead><tbody></tbody>';
    this.tbody = this.table.getElementsByTagName("tbody")[0];
    wrap.appendChild(this.table);

    this.mngInfo = el("div", "fu-mngno");
    this.status = el("div", "fu-status");
    wrap.appendChild(this.mngInfo);
    wrap.appendChild(this.status);
    box.appendChild(wrap);

    on(this.btnAdd, "click", function () {
      self.input.click();
    });
    on(this.btnDel, "click", function () {
      self.removeSelected();
    });
    on(this.input, "change", function () {
      self._onPick();
    });
    this._render();
  };

  /* ---- public API ---- */

  /* load existing files, or pass fileMngNo '' for a new record */
  GridFileUploader.prototype.setFile = function (p) {
    p = p || {};
    this.pathKey = p.pathKey || "";
    this.pgmId = p.pgmId || "";
    this.fileMngNo =
      p.fileMngNo == null ||
      p.fileMngNo === "null" ||
      p.fileMngNo === "undefined"
        ? ""
        : p.fileMngNo;
    this.rows = [];
    this.selected = {};
    this._render();
    if (this.fileMngNo) {
      this._load();
    }
    return this;
  };

  /* number to submit with the form ('' when nothing uploaded yet) */
  GridFileUploader.prototype.getFileMngNo = function () {
    return this.fileMngNo || "";
  };
  /* Share one fileMngNo between several controls:  a.link(b);
   * Whoever gets the number first (first upload) hands it to the others,
   * and while no number exists only one control uploads at a time. */
  GridFileUploader.prototype.link = function (other) {
    if (other === this) {
      return this;
    }
    this._addPeer(other);
    other._addPeer(this);
    return this;
  };
  GridFileUploader.prototype._addPeer = function (o) {
    var i;
    for (i = 0; i < this.peers.length; i++) {
      if (this.peers[i] === o) {
        return;
      }
    }
    this.peers.push(o);
  };
  GridFileUploader.prototype._peerBusy = function () {
    var i;
    for (i = 0; i < this.peers.length; i++) {
      if (this.peers[i].busy) {
        return true;
      }
    }
    return false;
  };
  /* set the number on this control and all peers */
  GridFileUploader.prototype._adopt = function (no) {
    var i;
    this.fileMngNo = no;
    for (i = 0; i < this.peers.length; i++) {
      this.peers[i].fileMngNo = no;
      this.peers[i]._render();
    }
  };

  /* true while files are queued or uploading */
  GridFileUploader.prototype.isBusy = function () {
    return this.busy || this.queue.length > 0;
  };
  GridFileUploader.prototype.getFileList = function () {
    return this.rows.slice(0);
  };

  /* Reject (called from the page, there is no button in this control):
   * re-upload ALL files of the shared number under a NEW fileMngNo.
   * The server copies every file of the current number into a new one and
   * returns it; this control then switches to the new number (the rejected
   * set is left untouched on the server). Call save() afterwards to store it. */
  GridFileUploader.prototype.hasFiles = function () {
    return !!this.fileMngNo && this.rows.length > 0;
  };

  GridFileUploader.prototype.reject = function (noConfirm, cb) {
    var self = this;
    if (this.isBusy()) {
      this._msg("Please wait until the uploads finish.", true);
      return;
    }
    if (!this.fileMngNo || !this.rows.length) {
      this._msg("There are no files to reject.", true);
      return;
    }
    if (
      !noConfirm &&
      !window.confirm(
        "Reject these files and re-upload them under a new file number?",
      )
    ) {
      return;
    }
    this._xhr(
      "POST",
      url("rejectFile.do"),
      "fileMngNo=" + enc(this.fileMngNo),
      null,
      function (err, res) {
        if (!err && res.ErrorCode === -1) {
          err = res.ErrorMsg || "Reject failed";
        }
        if (err) {
          self._msg(err, true);
          if (cb) {
            cb(err);
          }
          return;
        }
        var old = self.fileMngNo;
        self._adopt(res.FILE_MNG_NO); /* new number, also for linked controls */
        self.selected = {};
        self._load(); /* each control reloads its own files */
        var p;
        for (p = 0; p < self.peers.length; p++) {
          self.peers[p].selected = {};
          self.peers[p]._load();
        }
        self._msg(
          "Rejected " +
            old +
            ". Re-uploaded " +
            (res.DS_FILE_UPLOAD_LIST || []).length +
            " file(s) as " +
            self.fileMngNo +
            ".",
        );
        if (self.opts.onReject) {
          self.opts.onReject(old, self.fileMngNo);
        }
        self._changed();
        if (cb) {
          cb(null, old, self.fileMngNo);
        }
      },
    );
  };

  GridFileUploader.prototype.removeSelected = function () {
    var self = this,
      regNos = [],
      i;
    for (i = 0; i < this.rows.length; i++) {
      if (this.selected[this.rows[i].FILE_REG_NO]) {
        regNos.push(this.rows[i].FILE_REG_NO);
      }
    }
    if (!regNos.length) {
      this._msg("Select file(s) to delete.", true);
      return;
    }
    var finish = function () {
      var keep = [],
        j;
      for (j = 0; j < self.rows.length; j++) {
        if (!self.selected[self.rows[j].FILE_REG_NO]) {
          keep.push(self.rows[j]);
        }
      }
      self.rows = keep;
      self.selected = {};
      self._render();
      self._msg("Deleted.");
      self._changed();
    };
    this._xhr(
      "POST",
      url("deleteFile.do"),
      "fileMngNo=" +
        enc(this.fileMngNo) +
        "&fileRegNos=" +
        enc(regNos.join(",")),
      null,
      function (err) {
        if (err) {
          self._msg(err, true);
        } else {
          finish();
        }
      },
    );
  };

  /* ---- internals ---- */

  GridFileUploader.prototype._load = function () {
    var self = this;
    this._xhr(
      "GET",
      url("listFile.do") +
        "?fileMngNo=" +
        enc(this.fileMngNo) +
        "&pathKey=" +
        enc(this.pathKey),
      null,
      null,
      function (err, res) {
        if (err) {
          self._msg(err, true);
          return;
        }
        self.rows = res.DS_FILE_UPLOAD_LIST || [];
        self._render();
      },
    );
  };

  GridFileUploader.prototype._onPick = function () {
    var files = this.input.files,
      i,
      f,
      max = this.opts.maxSizeMB * 1024 * 1024;
    if (!files) {
      return;
    }
    for (i = 0; i < files.length; i++) {
      f = files[i];
      if (f.size > max) {
        this._msg(f.name + " exceeds " + this.opts.maxSizeMB + " MB.", true);
        continue;
      }
      this.queue.push({ file: f, pct: 0, state: "Waiting" });
      this._addPending(this.queue[this.queue.length - 1]);
    }
    try {
      this.input.value = "";
    } catch (e) {
      /* IE11: value reset may throw */
    }
    this._next(); /* AUTO UPLOAD */
  };

  /* sequential queue: guarantees one fileMngNo per control */
  GridFileUploader.prototype._next = function () {
    var self = this;
    if (this.busy || !this.queue.length) {
      return;
    }
    if (!this.fileMngNo && this._peerBusy()) {
      return;
    } /* peer is creating the number */
    this.busy = true;
    var job = this.queue[0];
    job.state = "Uploading";
    this._paintJob(job);

    var done = function (err, res) {
      self.queue.shift();
      self.busy = false;
      if (err) {
        job.state = "Failed";
        self._paintJob(job);
        self._msg(job.file.name + ": " + err, true);
      } else {
        /* >>> new file manager no returned by server is adopted here <<< */
        if (res.FILE_RESULT && res.FILE_RESULT.FILE_MNG_NO) {
          self._adopt(res.FILE_RESULT.FILE_MNG_NO);
        }
        self.rows.push(res.FILE_RESULT);
        self._msg("Uploaded " + job.file.name);
        self._changed();
      }
      if (job.tr && job.tr.parentNode) {
        job.tr.parentNode.removeChild(job.tr);
      }
      self._render();
      self._next(); /* next file reuses the same fileMngNo */
      var p;
      for (p = 0; p < self.peers.length; p++) {
        self.peers[p]._next();
      }
    };

    var fd = new FormData(); /* IE10+ */
    fd.append("upfile0", job.file, job.file.name);
    var upUrl =
      url("uploadFile.do") +
      "?mode=C&pathKey=" +
      enc(this.pathKey) +
      "&pgmId=" +
      enc(this.pgmId) +
      "&fileMngNo=" +
      enc(this.fileMngNo);
    this._xhr(
      "POST",
      upUrl,
      fd,
      function (pct) {
        job.pct = pct;
        self._paintJob(job);
      },
      function (err, res) {
        if (!err && res.ErrorCode === -1) {
          err = res.ErrorMsg || "Upload error";
        }
        done(err, res);
      },
    );
  };

  GridFileUploader.prototype._xhr = function (
    method,
    url,
    body,
    onProgress,
    cb,
  ) {
    var x = new XMLHttpRequest();
    x.open(method, url, true);
    if (typeof body === "string") {
      x.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
    }
    if (onProgress && x.upload) {
      x.upload.onprogress = function (e) {
        if (e.lengthComputable) {
          onProgress(Math.round((e.loaded * 100) / e.total));
        }
      };
    }
    x.onreadystatechange = function () {
      if (x.readyState !== 4) {
        return;
      }
      if (x.status < 200 || x.status >= 300) {
        cb("HTTP " + x.status);
        return;
      }
      try {
        cb(null, parseResponse(x.responseText));
      } catch (e) {
        cb(e.message);
      }
    };
    x.send(body || null);
  };

  /* fires opts.onChange(fileMngNo) after an upload, delete or reject */
  GridFileUploader.prototype._changed = function () {
    if (this.opts.onChange) {
      this.opts.onChange(this.fileMngNo);
    }
  };

  GridFileUploader.prototype._msg = function (t, isErr) {
    this.status.className = "fu-status" + (isErr ? " fu-err" : "");
    this.status.innerHTML = "";
    this.status.appendChild(document.createTextNode(t));
  };

  GridFileUploader.prototype._addPending = function (job) {
    var tr = el("tr");
    tr.innerHTML =
      '<td></td><td></td><td class="fu-c"></td><td class="fu-c"><div class="fu-st"></div><div class="fu-bar"><i></i></div></td>';
    tr.cells[1].appendChild(document.createTextNode(job.file.name));
    tr.cells[2].appendChild(
      document.createTextNode(formatBytes(job.file.size)),
    );
    job.tr = tr;
    this.tbody.appendChild(tr);
    this._paintJob(job);
  };

  GridFileUploader.prototype._paintJob = function (job) {
    if (!job.tr) {
      return;
    }
    var st = job.tr.getElementsByTagName("div")[0],
      bar = job.tr.getElementsByTagName("i")[0];
    st.innerHTML = "";
    st.appendChild(document.createTextNode(job.state));
    bar.style.width = (job.state === "Uploading" ? job.pct : 0) + "%";
  };

  GridFileUploader.prototype._render = function () {
    var self = this,
      i,
      r,
      tr,
      cb,
      a,
      pending = [];
    for (i = 0; i < this.queue.length; i++) {
      pending.push(this.queue[i].tr);
    }
    this.tbody.innerHTML = "";
    for (i = 0; i < this.rows.length; i++) {
      r = this.rows[i];
      tr = el("tr");
      if (this.selected[r.FILE_REG_NO]) {
        tr.className = "fu-sel";
      }
      cb = el("input");
      cb.type = "checkbox";
      cb.checked = !!this.selected[r.FILE_REG_NO];
      cb.setAttribute("data-reg", r.FILE_REG_NO);
      on(
        cb,
        "click",
        (function (reg, row) {
          return function () {
            if (this.checked) {
              self.selected[reg] = true;
              row.className = "fu-sel";
            } else {
              delete self.selected[reg];
              row.className = "";
            }
          };
        })(r.FILE_REG_NO, tr),
      );
      var td0 = el("td", "fu-c");
      td0.appendChild(cb);
      tr.appendChild(td0);
      a = el("span", "fu-name", r.LOGI_FILE_NM);
      on(
        a,
        "click",
        (function (row) {
          return function () {
            self.download(row);
          };
        })(r),
      );
      var td1 = el("td");
      td1.appendChild(a);
      tr.appendChild(td1);
      var td2 = el("td", "fu-c", formatBytes(r.FILE_SIZE));
      tr.appendChild(td2);
      tr.appendChild(el("td", "fu-c", "Done"));
      this.tbody.appendChild(tr);
    }
    for (i = 0; i < pending.length; i++) {
      if (pending[i]) {
        this.tbody.appendChild(pending[i]);
      }
    }
    this.mngInfo.innerHTML = "";
    this.mngInfo.appendChild(
      document.createTextNode(
        "fileMngNo: " + (this.fileMngNo || "(new - assigned on first upload)"),
      ),
    );
    var dis = !this.opts.editable;
    this.btnAdd.disabled = dis;
    this.btnDel.disabled = dis;
  };

  GridFileUploader.prototype.download = function (row) {
    var f =
      document.createElement("iframe"); /* hidden iframe, as in the original */
    f.style.display = "none";
    f.src =
      url("downloadFile.do") +
      "?fileRegNo=" +
      enc(row.FILE_REG_NO) +
      "&fileMngNo=" +
      enc(row.FILE_MNG_NO);
    document.body.appendChild(f);
    window.setTimeout(function () {
      if (f.parentNode) {
        f.parentNode.removeChild(f);
      }
    }, 30000);
  };

  GridFileUploader.baseUrl = "";
  window.GridFileUploader = GridFileUploader;
})(window, document);
