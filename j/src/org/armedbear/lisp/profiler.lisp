;;; profiler.lisp
;;;
;;; Copyright (C) 2003 Peter Graves
;;; $Id: profiler.lisp,v 1.1 2003-03-05 19:54:41 piso Exp $
;;;
;;; This program is free software; you can redistribute it and/or
;;; modify it under the terms of the GNU General Public License
;;; as published by the Free Software Foundation; either version 2
;;; of the License, or (at your option) any later version.
;;;
;;; This program is distributed in the hope that it will be useful,
;;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;; GNU General Public License for more details.
;;;
;;; You should have received a copy of the GNU General Public License
;;; along with this program; if not, write to the Free Software
;;; Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

(defun show-call-counts ()
  (let ((syms (list-calls)))
    (sort syms #'< :key #'(lambda (x) (function-call-count (fdefinition x))))
    (dolist (sym syms)
      (show-call-count-for-symbol sym)))
  (values))

;; Returns list of COMMON-LISP symbols with non-zero call counts.
(defun list-calls ()
  (let ((result nil))
    (dolist (sym (package-symbols :cl))
      (when (fboundp sym)
        (let* ((f (fdefinition sym))
               (n (function-call-count f)))
          (unless (zerop n)
            (setq result (cons sym result))))))
    result))

(defun show-call-count-for-symbol (sym)
  (format t "~A ~A~%" sym (function-call-count (fdefinition sym))))
