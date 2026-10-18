import java.nio.file.*;
import java.util.*;

/** What the import question says besides the counts, and what it can do besides choosing a calendar there is:
 *  when the file's entries fall, how many of them are already here - months the grid has not loaded included -
 *  importing into a new calendar named after the file, and a big import cancelled from Peergos' own progress bar,
 *  with the next file's question held back until that save is over. Each checked by what lands in the store.
 *
 *  An account of its own: it makes calendars and fills them.
 *
 *  Usage: java -cp ../server/Peergos.jar CalendarImportMoreTest.java [engine] [url]
 */
public class CalendarImportMoreTest {

    static String ev(String uid, String day, String summary) {
        return CalendarImportDialogTest.event(uid, day, summary);
    }

    static String weekly(String uid, String day, String summary) {
        return "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20260101T090000Z\r\nDTSTART:" + day + "T090000Z\r\n"
                + "DTEND:" + day + "T100000Z\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:" + summary + "\r\nEND:VEVENT\r\n";
    }

    static String undatedTask(String uid, String summary) {
        return "BEGIN:VTODO\r\nUID:" + uid + "\r\nDTSTAMP:20260101T090000Z\r\nSTATUS:NEEDS-ACTION\r\nSUMMARY:" + summary + "\r\nEND:VTODO\r\n";
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (AssertionError e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
    }

    public static void run(String[] args) throws Exception {
        String engine = args.length > 0 ? args[0] : "firefox";
        String given = args.length > 1 ? args[1] : null;
        boolean headless = ! "0".equals(System.getenv("HEADLESS"));
        Path serverDir = Paths.get("..", "server").toAbsolutePath().normalize();
        Server own = given == null ? Server.start(serverDir) : null;
        String url = own != null ? own.url() : given;
        String user = "importmore" + (System.currentTimeMillis() % 100000);
        try (WebDriver d = Browsers.launch(Browsers.engine(engine), Temp.directory("peergos-calendar-import-more-"), headless)) {
            Fixtures.signUp(url, user, CalendarLiveShareTest.PASSWORD);
            CalendarLiveShareTest.signIn(d, url, user);
            CalendarApp.open(d);
            CalendarApp.monthView(d);
            String main = CalendarApp.directoryOf(d, user);
            String target = CalendarWholeShareTest.addCalendar(d, CalendarImportDialogTest.TARGET, "Already in the target");
            // every toast that appears, kept for the report of a failure at the Cancel step below
            d.script("window.__toastLog = [];"
                    + "new MutationObserver(function () {"
                    + "  document.querySelectorAll('.Vue-Toastification__toast').forEach(function (t) {"
                    + "    let s = t.textContent.replace(/\\s+/g, ' ').trim().slice(0, 50).replace(/[0-9]+%/, '') + (t.querySelector('.progress__cancel') ? ' [Cancel]' : '');"
                    + "    if (window.__toastLog[window.__toastLog.length - 1] !== s) window.__toastLog.push(s);"
                    + "  });"
                    + "}).observe(document.body, {childList: true, subtree: true, characterData: true}); return 1;");

            // --- when the entries fall --------------------------------------------------------------
            String dated = CalendarImportDialogTest.calendar(ev("span-a", "20270301", "Span start"), ev("span-b", "20270621", "Span end"),
                    weekly("span-series", "20270405", "Span series"),
                    CalendarImportDialogTest.task("span-task", "20270310", "Dated task"), undatedTask("span-undated", "Undated task"));
            CalendarImportDialogTest.hand(d, List.of(List.of("spans.ics", dated)));
            awaitQuestion(d, "spans.ics");
            String events = text(d, "import-events-dates"), tasks = text(d, "import-tasks-dates");
            for (String part : new String[] {"Mar 1", "Jun 21, 2027", "1 repeating"})
                if (! events.contains(part))
                    throw new AssertionError("The events' dates should say " + part + ": " + events);
            for (String part : new String[] {"due Mar 10, 2027", "1 undated"})
                if (! tasks.contains(part))
                    throw new AssertionError("The tasks' dates should say " + part + ": " + tasks);
            System.out.println("  ok   when they fall: events [" + events + "], tasks [" + tasks + "]");
            CalendarApp.inFrame(d, CalendarApp.click("import-confirm") + "return 1;");
            CalendarImportDialogTest.expectSummary(d, "Imported 3 events.", "Imported 2 tasks.");

            // --- already here: on the page, and in months the grid has not loaded --------------------
            CalendarImportDialogTest.hand(d, List.of(List.of("spans.ics", dated)));
            awaitQuestion(d, "spans.ics");
            expectDuplicates(d, "5 of these are already in your calendars and will be skipped.");
            CalendarApp.inFrame(d, CalendarImportDialogTest.untick("import-tasks") + "return 1;");
            expectDuplicates(d, "3 of these are already in your calendars and will be skipped.");
            CalendarApp.inFrame(d, CalendarApp.click("import-cancel") + "return 1;");
            System.out.println("  ok   a second import of the same file says all five are already here, three with the tasks unticked");

            String farAway = String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:far-stored",
                    "DTSTAMP:20310101T090000Z", "DTSTART:20310114T090000Z", "DTEND:20310114T100000Z",
                    "SUMMARY:What the store already had", "END:VEVENT", "END:VCALENDAR", "");
            CalendarApp.write(d, main + "/2031/1", "far-stored.ics", farAway);
            String incoming = CalendarImportDialogTest.calendar(ev("far-stored", "20310114", "What the import carried"),
                    ev("far-new", "20310115", "Along for the ride"));
            CalendarImportDialogTest.hand(d, List.of(List.of("far.ics", incoming)));
            awaitQuestion(d, "far.ics");
            expectDuplicates(d, "1 of these is already in your calendars and will be skipped.");
            CalendarApp.inFrame(d, CalendarImportDialogTest.chooseCalendar(CalendarImportDialogTest.TARGET) + "return 1;");
            expectDuplicates(d, null);
            CalendarApp.inFrame(d, CalendarImportDialogTest.chooseCalendar("My Calendar") + "return 1;");
            expectDuplicates(d, "1 of these is already in your calendars and will be skipped.");
            System.out.println("  ok   an entry stored years away is counted, in the calendar that holds it and not in another");
            CalendarApp.inFrame(d, CalendarApp.click("import-confirm") + "return 1;");
            CalendarImportDialogTest.expectSummary(d, "Imported 1 event.", "1 already existed and was skipped.");
            CalendarImportDialogTest.awaitStored(d, main + "/2031/1", "far-new.ics");
            if (! CalendarApp.read(d, main + "/2031/1", "far-stored.ics").equals(farAway))
                throw new AssertionError("The import changed an entry it had said it would skip");
            System.out.println("  ok   and the summary agrees: the stored one is left exactly as it was");

            // --- a new calendar, named after the file ------------------------------------------------
            String offsite = CalendarImportDialogTest.calendar(ev("offsite-1", "20270901", "Offsite day one"),
                    ev("offsite-2", "20270902", "Offsite day two"));
            CalendarImportDialogTest.hand(d, List.of(List.of("Team Offsite!.ics", offsite), List.of("more-events.ics", CalendarImportDialogTest.MORE)));
            awaitQuestion(d, "Team Offsite!.ics (1 of 2)");
            CalendarApp.inFrame(d, CalendarImportDialogTest.chooseCalendar("New calendar…") + "return 1;");
            String suggested = String.valueOf(CalendarApp.inFrame(d, "return document.getElementById('import-new-name').value;"));
            if (! suggested.equals("Team Offsite"))
                throw new AssertionError("A new calendar should be named after the file, as a calendar name may be: " + suggested);
            for (String[] bad : new String[][] {{"Bad/Name", "Use only letters, digits, spaces, - and _."},
                    {"My Calendar", "There is already a calendar called My Calendar."}, {"   ", "Give the new calendar a name."}}) {
                Object said = CalendarApp.inFrame(d, CalendarApp.setField("import-new-name", bad[0])
                        + "let e = document.getElementById('import-new-error');"
                        + "return (e.hidden ? 'no error' : e.textContent) + ' | ' + (document.getElementById('import-confirm').disabled ? 'disabled' : 'enabled');");
                if (! (bad[1] + " | disabled").equals(String.valueOf(said)))
                    throw new AssertionError("The name [" + bad[0] + "] should be refused with \"" + bad[1] + "\": " + said);
            }
            System.out.println("  ok   suggested \"" + suggested + "\"; a slash, a name in use and a blank one are refused, Import disabled");
            CalendarApp.inFrame(d, CalendarApp.setField("import-new-name", "Offsite 2027") + CalendarApp.click("import-confirm") + "return 1;");
            awaitQuestion(d, "more-events.ics (2 of 2)");
            String startsOn = String.valueOf(CalendarApp.inFrame(d, "let s = document.getElementById('import-calendar'); return s.options[s.selectedIndex].textContent;"));
            if (! startsOn.equals("Offsite 2027"))
                throw new AssertionError("The next file should start on the calendar the last one was made into: " + startsOn);
            CalendarApp.inFrame(d, CalendarApp.click("import-cancel") + "return 1;");
            CalendarImportDialogTest.expectSummary(d, "Team Offsite!.ics: Imported 2 events.", "more-events.ics: Not imported.");
            Object made = d.script("let c = (window.__cal.calendarProperties.calendars || []).find(c => c.name === 'Offsite 2027'); return c ? c.directory : null;");
            if (made == null)
                throw new AssertionError("The host has no calendar called Offsite 2027: " + CalendarApp.hostState(d));
            CalendarImportDialogTest.awaitStored(d, made + "/2027/9", "offsite-1.ics", "offsite-2.ics");
            CalendarApp.waitInFrame(d, "the new calendar in the list",
                    "Array.from(document.querySelectorAll('.calendar-list-item')).some(el => el.textContent.indexOf('Offsite 2027') !== -1)", 30_000);
            System.out.println("  ok   Offsite 2027 is made, listed and holds both entries; the next file started on it");

            // --- a big import cancelled from the progress bar, the next file waiting for it -----------
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 300; i++)
                many.append(ev("bulk-" + i, String.format("2028%02d%02d", 1 + i % 12, 1 + i % 28), "Bulk " + i));
            String big = CalendarImportDialogTest.calendar(many.toString());
            CalendarImportDialogTest.hand(d, List.of(List.of("big.ics", big), List.of("small.ics", CalendarImportDialogTest.MORE)));
            awaitQuestion(d, "big.ics (1 of 2)");
            // Pressed the moment Peergos' progress bar offers it - a few hundred small files are
            // written in about a second here - and the time the next question opened kept beside it.
            d.script("window.__cancelledAt = null;"
                    + "new MutationObserver(function (changes, watcher) {"
                    + "  let bar = Array.from(document.querySelectorAll('.Vue-Toastification__toast')).find(t => t.textContent.indexOf('Importing 300') !== -1);"
                    + "  let cancel = bar && bar.querySelector('.progress__cancel');"
                    + "  if (!cancel) return;"
                    + "  watcher.disconnect(); window.__cancelledAt = Date.now(); cancel.click();"
                    + "}).observe(document.body, {childList: true, subtree: true}); return 1;");
            CalendarApp.inFrame(d, "window.__nextAskedAt = null;"
                    + "new MutationObserver(function () {"
                    + "  if (window.__nextAskedAt == null && document.getElementById('import-file').textContent.indexOf('2 of 2') !== -1"
                    + "      && document.getElementById('import-modal-backdrop').classList.contains('open')) window.__nextAskedAt = Date.now();"
                    + "}).observe(document.body, {childList: true, subtree: true, attributes: true, characterData: true});"
                    + CalendarApp.click("import-confirm") + "return 1;");
            try {
                d.waitForScript("Cancel on the import's progress bar, pressed", "window.__cancelledAt != null", 60_000);
            } catch (IllegalStateException e) {
                System.out.println("  toast log: " + d.scriptQuiet("return window.__toastLog.join(' | ')"));
                System.out.println("  frame: " + CalendarApp.inFrame(d, "return 'question ' + document.getElementById('import-modal-backdrop').classList.contains('open') + ' ' + document.getElementById('import-file').textContent"
                        + " + ', import ' + (document.getElementById('import-confirm').disabled ? 'disabled' : 'enabled') + ' [' + document.getElementById('import-confirm').textContent + ']'"
                        + " + ', new error ' + document.getElementById('import-new-error').textContent + ', calendar ' + document.getElementById('import-calendar').value"
                        + " + ', summary ' + document.getElementById('import-summary-modal-backdrop').classList.contains('open');"));
                throw e;
            }
            awaitQuestion(d, "small.ics (2 of 2)");
            long cancelledAt = ((Number) d.script("return window.__cancelledAt;")).longValue();
            long nextAskedAt = ((Number) CalendarApp.inFrame(d, "return window.__nextAskedAt;")).longValue();
            if (nextAskedAt < cancelledAt)
                throw new AssertionError("The next file was asked about " + (cancelledAt - nextAskedAt) + "ms before the first one's save was over");
            CalendarApp.inFrame(d, CalendarApp.click("import-cancel") + "return 1;");
            int stored = 0;
            for (int m = 1; m <= 12; m++)
                stored += CalendarImportDialogTest.listOrEmpty(d, main + "/2028/" + m).size();
            CalendarImportDialogTest.expectSummary(d, "big.ics: Cancelled while saving", "small.ics: Not imported.");
            if (stored >= 300)
                throw new AssertionError("Cancelling should stop the save part way, but all " + stored + " were stored");
            System.out.println("  ok   cancelled with " + stored + " of 300 stored; the next question waited for the save to end");
            System.out.println("PASS");
        } finally {
            if (own != null)
                own.close();
        }
    }

    static void awaitQuestion(WebDriver d, String file) {
        CalendarApp.waitInFrame(d, "the import question about " + file,
                "document.getElementById('import-modal-backdrop').classList.contains('open')"
                        + " && document.getElementById('import-file').textContent === " + CalendarApp.quote(file), 120_000);
    }

    /** An element's text in the app, with the narrow spaces some engines put around a date range made plain. */
    static String text(WebDriver d, String id) {
        return String.valueOf(CalendarApp.inFrame(d, "return document.getElementById(arguments[0]).textContent.replace(/[\\u2009\\u202f]/g, ' ');", id));
    }

    /** Waits for the already-here line to settle on `expected`, or to be hidden when that is null. */
    static void expectDuplicates(WebDriver d, String expected) {
        String condition = expected == null
                ? "document.getElementById('import-duplicates').hidden"
                : "!document.getElementById('import-duplicates').hidden && document.getElementById('import-duplicates').textContent === " + CalendarApp.quote(expected);
        try {
            CalendarApp.waitInFrame(d, "the already-here line to say " + expected, condition, 60_000);
        } catch (IllegalStateException e) {
            throw new AssertionError("The already-here line should say " + expected + ", not: "
                    + CalendarApp.inFrame(d, "let l = document.getElementById('import-duplicates'); return (l.hidden ? '(hidden) ' : '') + l.textContent;"));
        }
    }
}
