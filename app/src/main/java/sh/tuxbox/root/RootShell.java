// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 LongQT-sea
package sh.tuxbox.root;

import com.topjohnwu.superuser.Shell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RootShell {
    private RootShell() {}

    /** Single shared executor for root commands so we never block the UI thread. */
    public static final ExecutorService EXEC = Executors.newFixedThreadPool(2);

    /**
     * v3.10 — pool elástico para os jobs de root: um job pendurado (su/libsu
     * travado, como o daemon do KernelSU em crash) fica preso na SUA thread e
     * não ocupa as duas do pool, evitando travar toda a UI.
     */
    private static final ExecutorService JOBS = Executors.newCachedThreadPool();

    /** Segundos que esperamos por um comando de root antes de desistir dele. */
    private static final long TIMEOUT_S = 12;

    public interface Callback {
        void done(String output);
    }

    /**
     * v3.10 — o callback SEMPRE dispara: mesmo que o `su` fique pendurado
     * indefinidamente, a UI recebe resposta em ~TIMEOUT_S s em vez de ficar
     * girando (o libsu 5.2.2 não tem timeout nativo).
     */
    public static void runAsync(String cmd, Callback cb) {
        EXEC.execute(() -> {
            String out;
            Future<String> job = JOBS.submit(() -> run(cmd));
            try {
                out = job.get(TIMEOUT_S, TimeUnit.SECONDS);
            } catch (TimeoutException t) {
                job.cancel(true);
                out = "[timeout " + TIMEOUT_S + "s ao esperar root] " + cmd;
            } catch (Exception t) {
                out = "[erro ao rodar root: " + t + "]";
            }
            cb.done(out);
        });
    }

    /** Run a root command synchronously returning combined stdout/stderr. */
    public static String run(String cmd) {
        Shell.Job job = Shell.cmd(cmd);
        Shell.Result res = job.exec();
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(cmd).append("\n");
        for (String line : res.getOut()) {
            sb.append(line).append("\n");
        }
        for (String line : res.getErr()) {
            sb.append("ERR: ").append(line).append("\n");
        }
        sb.append("[exit=").append(res.getCode()).append("]\n");
        return sb.toString();
    }

    /** Return true if the su shell actually has uid 0. */
    public static boolean isRoot() {
        try {
            Shell shell = Shell.getShell();
            return shell != null && shell.isRoot();
        } catch (Throwable t) {
            return false;
        }
    }
}