package uk.co.gresearch.siembol.alerts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.reinert.jjschema.Attributes;

@Attributes(title = "rule protection", description = "Thresholds for deactivating the rule")
public class RuleProtectionDto {
    @JsonProperty("max_per_hour")
    @Attributes(required = true, description = "Maximum alerts generated by the rule per hour")
    Integer maxPerHour = 30;
    @JsonProperty("max_per_day")
    @Attributes(required = true, description = "Maximum alerts generated by the rule per hour")
    Integer maxPerDay = 100;

    public Integer getMaxPerHour() {
        return maxPerHour;
    }

    public void setMaxPerHour(Integer maxPerHour) {
        this.maxPerHour = maxPerHour;
    }

    public Integer getMaxPerDay() {
        return maxPerDay;
    }

    public void setMaxPerDay(Integer maxPerDay) {
        this.maxPerDay = maxPerDay;
    }
}