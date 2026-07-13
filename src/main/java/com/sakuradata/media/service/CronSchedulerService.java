package com.sakuradata.media.service;

import com.sakuradata.media.model.CronJob;
import com.sakuradata.media.repository.CronJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CronSchedulerService {

    @Autowired
    private CronJobRepository cronJobRepository;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Run every 1 minute
    @Scheduled(cron = "0 * * * * *")
    public void checkAndRunJobs() {
        List<CronJob> jobs = cronJobRepository.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (CronJob job : jobs) {
            if (!job.isEnabled()) {
                continue;
            }

            try {
                CronExpression expression = CronExpression.parse(job.getCronExpression());
                
                // If lastRun is null, check if we should run it. We assume we check from 1 minute ago.
                LocalDateTime lastCheck = (job.getLastRun() != null) ? 
                    LocalDateTime.ofInstant(job.getLastRun().toInstant(), ZoneId.systemDefault()) : 
                    now.minusMinutes(1).minusSeconds(5);

                LocalDateTime nextExecution = expression.next(lastCheck);

                if (nextExecution != null && (nextExecution.isBefore(now) || nextExecution.isEqual(now))) {
                    // Trigger job execution asynchronously
                    executor.submit(() -> executeJob(job));
                }
            } catch (Exception e) {
                System.err.println("Invalid cron expression for job " + job.getName() + ": " + e.getMessage());
            }
        }
    }

    private void executeJob(CronJob job) {
        System.out.println("Starting scheduled task execution: " + job.getName());
        job.setLastRun(new Date());
        job.setStatus("Running");
        cronJobRepository.save(job);

        try {
            String[] cmd;
            if ("python".equalsIgnoreCase(job.getType())) {
                cmd = new String[]{"python3", "-c", job.getCommand()};
            } else if ("docker".equalsIgnoreCase(job.getType())) {
                cmd = new String[]{"docker", "run", "--rm", job.getCommand()};
            } else {
                // Default shell script
                cmd = new String[]{"sh", "-c", job.getCommand()};
            }

            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            
            // Read first few lines of output
            StringBuilder output = new StringBuilder();
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead < 5) {
                output.append(line).append("\n");
                linesRead++;
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                job.setStatus("Success: " + output.toString().trim());
            } else {
                String error = errorReader.readLine();
                job.setStatus("Failed (exit " + exitCode + "): " + (error != null ? error : ""));
            }
        } catch (Exception e) {
            job.setStatus("Error: " + e.getMessage());
        }

        cronJobRepository.save(job);
        System.out.println("Finished scheduled task execution: " + job.getName() + " with status: " + job.getStatus());
    }
}
