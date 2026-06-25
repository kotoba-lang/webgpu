(ns ical-test
  "Golden tests for kami.ical — the iCalendar (RFC 5545) hiccup. They pin BEGIN/END components, NAME:
   value properties, TEXT escaping (\\; \\, \\\\ for summary/location), structural properties passing
   through unescaped (RRULE), CRLF line endings, and the VCALENDAR VERSION/PRODID header."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kami.ical :as ic]))

(deftest a-calendar
  (let [src (ic/vcalendar {:prodid "-//me//EN"}
              (ic/vevent {:uid "1@x" :dtstart "20260625T100000Z" :dtend "20260625T110000Z"
                          :summary "Meeting; A,B" :location "Room A" :rrule "FREQ=WEEKLY;COUNT=10"})
              (ic/vtodo {:uid "2@x" :summary "Buy milk"}))
        lines (str/split src #"\r\n")]
    (is (str/includes? src "\r\n") "CRLF line endings")
    (is (= "BEGIN:VCALENDAR" (first lines)))
    (is (= ["VERSION:2.0" "PRODID:-//me//EN"] (subvec (vec lines) 1 3)) "version + prodid header")
    (is (some #(= "SUMMARY:Meeting\\; A\\,B" %) lines) "TEXT escaped (; , →  \\; \\,)")
    (is (some #(= "RRULE:FREQ=WEEKLY;COUNT=10" %) lines) "structural RRULE unescaped")
    (is (some #(= "BEGIN:VEVENT" %) lines))
    (is (some #(= "BEGIN:VTODO" %) lines))
    (is (= "END:VCALENDAR" (last lines)))
    (is (= (count (filter #(= "BEGIN:VEVENT" %) lines))
           (count (filter #(= "END:VEVENT" %) lines))) "balanced BEGIN/END")))

(deftest line-folding
  ;; RFC 5545 §3.1: content lines >75 octets must be folded (continuation lines begin with a space).
  (let [long "This is a very long event description that comfortably exceeds the seventy-five octet limit mandated by RFC 5545 and must be folded onto continuation lines."
        src  (ic/vcalendar {} (ic/vevent {:uid "1" :description long}))
        ls   (str/split src #"\r\n")]
    (is (every? #(<= (count %) 75) ls) "no content line exceeds 75 octets")
    (is (some #(str/starts-with? % " ") ls) "a continuation line begins with a space")
    (is (some #(= "UID:1" %) ls) "short lines are left unfolded")
    ;; unfolding (remove every CRLF+space fold point) restores the original content line
    (is (str/includes? (str/replace src #"\r\n " "") (str "DESCRIPTION:" long))
        "unfolding recovers the description value")))

(deftest component-builders
  (is (= ["BEGIN:VEVENT" "UID:a" "SUMMARY:Hi" "END:VEVENT"] (ic/vevent {:uid "a" :summary "Hi"})))
  (is (= ["BEGIN:VALARM" "ACTION:DISPLAY" "END:VALARM"] (ic/valarm {:action "DISPLAY"}))))

(let [{:keys [fail error]} (run-tests 'ical-test)]
  (when (pos? (+ fail error))
    (throw (ex-info "ical tests failed" {:fail fail :error error}))))
