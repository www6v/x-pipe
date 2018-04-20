package com.ctrip.xpipe.redis.console.alert.message;

import com.ctrip.xpipe.command.AbstractCommand;
import com.ctrip.xpipe.command.SequenceCommandChain;
import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.redis.console.alert.ALERT_TYPE;
import com.ctrip.xpipe.redis.console.alert.AlertEntity;
import com.ctrip.xpipe.redis.console.alert.AlertMessageEntity;
import com.ctrip.xpipe.redis.console.alert.policy.receiver.EmailReceiverModel;
import com.ctrip.xpipe.redis.console.alert.sender.AbstractSender;
import com.ctrip.xpipe.tuple.Pair;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author chen.zhu
 * <p>
 * Apr 20, 2018
 */
@Component
public class RepeatAlertEntitySubscriber extends AbstractAlertEntitySubscriber {

    private Map<ALERT_TYPE, Set<AlertEntity>> repeatAlerts = Maps.newConcurrentMap();

    @PostConstruct
    public void scheduledTask() {
        scheduled.scheduleWithFixedDelay(new AbstractExceptionLogTask() {
            @Override
            protected void doRun() throws Exception {

                Map<ALERT_TYPE, Set<AlertEntity>> alerts;
                synchronized (this) {
                    alerts = refresh();
                }
                if(alerts == null || alerts.isEmpty()) {
                    return;
                }

                SequenceCommandChain chain = new SequenceCommandChain();

                chain.add(new ScheduledCleanupExpiredAlertTask(alerts));
                chain.add(new ScheduledSendRepeatAlertTask(alerts));

                chain.execute(executors);

            }
        }, 1, consoleConfig().getAlertSystemSuspendMinute(), TimeUnit.MINUTES);
    }

    @Override
    protected void doProcessAlert(AlertEntity alert) {
        synchronized (this) {
            repeatAlerts.putIfAbsent(alert.getAlertType(), Sets.newConcurrentHashSet());
        }
        Set<AlertEntity> alerts = repeatAlerts.get(alert.getAlertType());
        for(int i = 0; i < 3 && !alerts.add(alert); i++) {
            alerts.remove(alert);
        }

    }

    private Map<ALERT_TYPE, Set<AlertEntity>> refresh() {
        Map<ALERT_TYPE, Set<AlertEntity>> alerts = repeatAlerts;
        repeatAlerts = Maps.newConcurrentMap();
        return alerts;
    }

    private AlertMessageEntity getMessage(EmailReceiverModel receivers, Map<ALERT_TYPE, Set<AlertEntity>> alerts) {

        Pair<String, String> titleAndContent = decoratorManager().generateTitleAndContent(alerts);
        AlertMessageEntity message = new AlertMessageEntity(titleAndContent.getKey(), titleAndContent.getValue(), receivers.getRecipients());
        message.addParam(AbstractSender.CC_ER, receivers.getCcers());

        return message;
    }

    class ScheduledSendRepeatAlertTask extends AbstractCommand<Void> {

        private Map<ALERT_TYPE, Set<AlertEntity>> alerts;

        public ScheduledSendRepeatAlertTask(Map<ALERT_TYPE, Set<AlertEntity>> alerts) {
            this.alerts = alerts;
        }

        @Override
        protected void doExecute() throws Exception {
            Map<EmailReceiverModel, Map<ALERT_TYPE, Set<AlertEntity>>> map = alertPolicyManager().queryGroupedEmailReceivers(alerts);
            for(Map.Entry<EmailReceiverModel, Map<ALERT_TYPE, Set<AlertEntity>>> mailGroup : map.entrySet()) {
                AlertMessageEntity message = getMessage(mailGroup.getKey(), mailGroup.getValue());
                emailMessage(message);
            }
        }

        @Override
        protected void doReset() {

        }

        @Override
        public String getName() {
            return ScheduledSendRepeatAlertTask.class.getSimpleName();
        }

    }

    class ScheduledCleanupExpiredAlertTask extends AbstractCommand<Void> {

        private Map<ALERT_TYPE, Set<AlertEntity>> alerts;

        public ScheduledCleanupExpiredAlertTask(Map<ALERT_TYPE, Set<AlertEntity>> alerts) {
            this.alerts = alerts;
        }

        @Override
        protected void doExecute() throws Exception {
            for(ALERT_TYPE type : alerts.keySet()) {
                Set<AlertEntity> alertEntitySet = alerts.get(type);
                alertEntitySet.removeIf(alert -> alertRecovered(alert));
                alerts.put(type, alertEntitySet);
            }
        }

        @Override
        protected void doReset() {

        }

        @Override
        public String getName() {
            return ScheduledCleanupExpiredAlertTask.class.getSimpleName();
        }
    }
}
