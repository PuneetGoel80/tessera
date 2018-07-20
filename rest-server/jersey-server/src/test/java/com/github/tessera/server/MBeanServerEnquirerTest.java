package com.github.tessera.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import javax.management.*;

public class MBeanServerEnquirerTest {

    private MBeanServerEnquirer enquirer;

    @Mock
    private MBeanServer mockMBeanServer;

    @Mock
    private MBeanInfo mockMBeanInfo;

    private ObjectName mockMBeanName;

    @Before
    public void setUp() throws IntrospectionException, InstanceNotFoundException, ReflectionException, MalformedObjectNameException {
        MockitoAnnotations.initMocks(this);
        MBeanAttributeInfo[] mockMBeanAttributeInfo = null;
        mockMBeanName = new ObjectName("test:foo=bar");
        when(mockMBeanServer.getMBeanInfo(mockMBeanName)).thenReturn(mockMBeanInfo);

        enquirer = new MBeanServerEnquirer(this.mockMBeanServer);
    }

    @Test
    public void mBeanNameQueryPatternIsCorrectlyFormattedAndDoesNotThrowException() throws MalformedObjectNameException {
        enquirer.getTesseraResourceMBeanNames();
    }

    @Test
    public void ifAttributeNameNotEndingWithTotalThenMetricsNotRetrieved() throws IntrospectionException, AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException {
        MBeanAttributeInfo[] mockMBeanAttributeInfo = {new MBeanAttributeInfo("name", "type", "description", true, false, false)};
        when(mockMBeanInfo.getAttributes()).thenReturn(mockMBeanAttributeInfo);

        assertThat(enquirer.getMetricsForMBean(mockMBeanName).size()).isEqualTo(0);
    }

    @Test
    public void ifAttributeNameEndingWithTotalThenMetricsRetrieved() throws IntrospectionException, AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException {
        MBeanAttributeInfo[] mockMBeanAttributeInfo = {new MBeanAttributeInfo("name_total", "type", "description", true, false, false)};
        when(mockMBeanInfo.getAttributes()).thenReturn(mockMBeanAttributeInfo);

        int someValue = 10;
        when(mockMBeanServer.getAttribute(any(ObjectName.class), any(String.class))).thenReturn(someValue);

        assertThat(enquirer.getMetricsForMBean(mockMBeanName).size()).isEqualTo(1);
        assertThat(enquirer.getMetricsForMBean(mockMBeanName).get(0).getName()).isEqualTo("name_total");
    }

    @Test
    public void metricsFromMultipleAttributesAreRetrieved() throws IntrospectionException, AttributeNotFoundException, MBeanException, ReflectionException, InstanceNotFoundException {
        MBeanAttributeInfo[] mockMBeanAttributeInfo = {
            new MBeanAttributeInfo("first_total", "type", "description", true, false, false),
            new MBeanAttributeInfo("second_total", "type", "description", true, false, false),
            new MBeanAttributeInfo("third", "type", "description", true, false, false)
        };
        when(mockMBeanInfo.getAttributes()).thenReturn(mockMBeanAttributeInfo);

        int someValue = 10;
        when(mockMBeanServer.getAttribute(any(ObjectName.class), any(String.class))).thenReturn(someValue);

        assertThat(enquirer.getMetricsForMBean(mockMBeanName).size()).isEqualTo(2);
        assertThat(enquirer.getMetricsForMBean(mockMBeanName).get(0).getName()).isEqualTo("first_total");
        assertThat(enquirer.getMetricsForMBean(mockMBeanName).get(1).getName()).isEqualTo("second_total");
    }

}
